@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package tech.imitravanu.eroflix.feature.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.imitravanu.eroflix.core.media.PlaybackPhase
import tech.imitravanu.eroflix.core.media.ProductionPlayback
import tech.imitravanu.eroflix.core.network.ContainedWebPlayer
import tech.imitravanu.eroflix.core.ui.formatTime
import tech.imitravanu.eroflix.model.*

val PlayerCropMode = SemanticsPropertyKey<Boolean>("PlayerCropMode")

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlayerScreen(
    owner: ProductionPlayback,
    catalog: CatalogRepository,
    onDownload: suspend (DownloadIntent) -> Outcome<JobId> = { Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Downloads skipped")) },
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSourceChanged: (SourceHandle) -> Unit = {},
    onDetectedDownload: suspend (DownloadIntent, DetectedStream) -> Outcome<JobId> = { _, _ -> Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Downloads skipped")) },
    requestSubtitle: (() -> Unit)? = null,
    pendingSubtitle: Uri? = null,
    onSubtitleConsumed: () -> Unit = {}
) {
    val ui by owner.ui.collectAsStateWithLifecycle()
    val player by owner.surfacePlayer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var crop by rememberSaveable { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var sources by remember(ui.title?.id) { mutableStateOf<List<SourceHandle>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<ContainedWebPlayer?>(null) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    val originalOrientation = remember { activity.requestedOrientation }
    val disallow = remember { RequestDisallowInterceptTouchEvent() }
    val gestures = remember(owner, activity) {
        PlayerGestureRouter(activity, owner, { false }, { crop = it }, disallow::invoke)
    }

    // Auto-hide controls when playing
    LaunchedEffect(controlsVisible, ui.phase) {
        if (controlsVisible && ui.phase == PlaybackPhase.PLAYING) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Load available source mirrors for this title
    LaunchedEffect(ui.title?.id) {
        ui.title?.let { title ->
            sources = (withContext(Dispatchers.IO) { catalog.details(title.id) } as? Outcome.Success)
                ?.value?.sources.orEmpty()
        }
    }

    // External Subtitle Picker
    fun acceptSubtitle(uri: Uri?, onComplete: () -> Unit = {}) {
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch(Dispatchers.IO) {
                var detectedMime = MimeTypes.APPLICATION_SUBRIP
                val valid = runCatching {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Subtitle could not be opened." }.use { input ->
                        val bytes = input.readNBytes(1024 * 1024 + 1)
                        require(bytes.size <= 1024 * 1024) { "Subtitle exceeds 1 MiB." }
                        val text = bytes.toString(Charsets.UTF_8)
                        detectedMime = if (text.trimStart().startsWith("WEBVTT")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                        require(Regex("[0-9]{2}:[0-9]{2}(?::[0-9]{2})?[.,][0-9]{3}\\s+-->\\s+[0-9]{2}:[0-9]{2}").containsMatchIn(text)) {
                            "No valid subtitle timestamps found. Choose SRT or WebVTT."
                        }
                    }
                }.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    if (valid != null) {
                        message = valid.message ?: "Subtitle could not be read."
                    } else {
                        message = when (val result = owner.subtitle(uri, detectedMime)) {
                            is Outcome.Failure -> result.error.message
                            is Outcome.Success -> "External subtitle loaded."
                        }
                    }
                    onComplete()
                }
            }
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        acceptSubtitle(uri)
    }

    LaunchedEffect(pendingSubtitle) {
        if (pendingSubtitle != null) acceptSubtitle(pendingSubtitle, onSubtitleConsumed)
    }

    // Lifecycle handling
    DisposableEffect(owner, lifecycle) {
        owner.closeWeb = { web?.close(); web = null }
        owner.onLockUi = { if (activity.isInPictureInPictureMode) activity.finish() }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                gestures.cancel()
                owner.visibility(false, activity.isInPictureInPictureMode)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
            }
            if (event == Lifecycle.Event.ON_START) {
                owner.visibility(true, activity.isInPictureInPictureMode)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            gestures.cancel()
            lifecycle.lifecycle.removeObserver(observer)
            web?.close()
            web = null
            owner.closeWeb = null
            owner.onLockUi = null
            owner.leaveRoute()
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
            activity.requestedOrientation = originalOrientation
        }
    }

    // Picture in Picture rendering
    if (ui.inPip) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false } },
            update = {
                it.player = player
                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize().testTag("pip-video-surface")
        )
        return
    }

    // Fullscreen Immersive Player Box
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
            }
    ) {
        // 1. VIDEO RENDERING LAYER
        if (ui.web != null) {
            val resolution = ui.web!!
            val policy = remember(resolution.entryUri) { owner.webPolicy(resolution) }
            if (policy != null) {
                AndroidView(
                    factory = { ctx ->
                        ContainedWebPlayer(ctx, policy, owner::pause, onBack, { message = it.message },
                            localAsset = owner.webResourceOverride).also { web = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.close(); if (web === it) web = null }
                )
            }
        } else {
            // Media3 Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { this[PlayerCropMode] = crop }
                    .onSizeChanged { surfaceSize = it }
            ) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )

                // Gesture Router Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInteropFilter(requestDisallowInterceptTouchEvent = disallow) { event ->
                            gestures.touch(event, surfaceSize.width.toFloat(), surfaceSize.height.toFloat())
                        }
                )
            }
        }

        // 2. LOADING STATE OVERLAY
        if (ui.phase == PlaybackPhase.RESOLVING || ui.phase == PlaybackPhase.BUFFERING) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFE11D48),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = if (ui.phase == PlaybackPhase.RESOLVING) "Connecting to stream…" else "Buffering…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // 3. ERROR / FALLBACK STATE
        if (ui.phase == PlaybackPhase.FAILED || ui.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF141418),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE11D48).copy(alpha = 0.5f)),
                    modifier = Modifier.padding(32.dp).widthIn(max = 420.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Playback Interrupted",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = ui.error?.message ?: "Could not stream video from this server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1AA)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (sources.size > 1) {
                                Button(
                                    onClick = { showSources = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                                ) {
                                    Text("Switch Server")
                                }
                            }
                            OutlinedButton(
                                onClick = { scope.launch { owner.retry() } },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("Retry")
                            }
                            TextButton(onClick = onBack) {
                                Text("Back", color = Color(0xFFA1A1AA))
                            }
                        }
                    }
                }
            }
        }

        // 4. ANIMATED CONTROLS HUD OVERLAY
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Gradient Scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(onClick = onBack) {
                                Text("‹", color = Color.White, fontSize = 28.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = ui.title?.title ?: "Playing Video",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Source / Mirror switcher chip
                            if (sources.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF1E1E24),
                                    modifier = Modifier.clickable { showSources = true }
                                ) {
                                    Text(
                                        text = ui.source?.provider?.uppercase() ?: "SERVER",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF59E0B),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            // Subtitle picker button
                            IconButton(onClick = {
                                if (requestSubtitle != null) requestSubtitle() else subtitlePicker.launch(arrayOf("text/*", "application/x-subrip"))
                            }) {
                                Text("CC", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            // Rotate Screen Button
                            IconButton(onClick = {
                                val current = activity.resources.configuration.orientation
                                activity.requestedOrientation = if (current == 2) {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                            }) {
                                Text("⛶", color = Color.White, fontSize = 18.sp)
                            }

                            // PiP Button
                            IconButton(onClick = onPip) {
                                Text("⊡", color = Color.White, fontSize = 18.sp)
                            }
                        }
                    }
                }

                // Center Play / Pause & Skip Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp).clickable { owner.seekTo(ui.positionMs - 10_000) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("↺ 10", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Large Play / Pause
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE11D48),
                        modifier = Modifier.size(72.dp).clickable {
                            if (ui.phase == PlaybackPhase.PLAYING) owner.pause() else owner.play()
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (ui.phase == PlaybackPhase.PLAYING) "❚❚" else "▶",
                                color = Color.White,
                                fontSize = 26.sp
                            )
                        }
                    }

                    // Forward 10s
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp).clickable { owner.seekTo(ui.positionMs + 10_000) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("10 ↻", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Bottom Gradient Scrim & Scrub Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Timeline Slider
                    ui.durationMs?.let { duration ->
                        if (duration > 0) {
                            Slider(
                                value = ui.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                                onValueChange = { owner.seekTo(it.toLong()) },
                                valueRange = 0f..duration.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFE11D48),
                                    activeTrackColor = Color(0xFFE11D48),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.fillMaxWidth().height(24.dp)
                            )
                        }
                    }

                    // Bottom Row: Timestamps, Audio Gain, Crop
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1AA),
                            fontWeight = FontWeight.Medium
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Audio Boost Toggle (1.0x -> 1.5x -> 2.0x -> 1.0x)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (ui.gain > 1.05f) Color(0xFFE11D48).copy(alpha = 0.25f) else Color(0xFF1E1E24),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (ui.gain > 1.05f) Color(0xFFE11D48) else Color(0xFF2E2E36)
                                ),
                                modifier = Modifier.clickable {
                                    val nextGain = when {
                                        ui.gain < 1.4f -> 1.5f
                                        ui.gain < 1.9f -> 2.0f
                                        else -> 1.0f
                                    }
                                    owner.gain(nextGain)
                                }
                            ) {
                                Text(
                                    text = if (ui.gain > 1.05f) "🔊 Boost ${"%.1f".format(ui.gain)}×" else "🔊 Normal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (ui.gain > 1.05f) Color(0xFFE11D48) else Color(0xFFA1A1AA),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Aspect Ratio Toggle
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1E1E24),
                                modifier = Modifier.clickable { crop = !crop }
                            ) {
                                Text(
                                    text = if (crop) "Zoom" else "Fit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA1A1AA),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Track / Quality modal
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1E1E24),
                                modifier = Modifier.clickable { showTracks = true }
                            ) {
                                Text(
                                    text = "Quality",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA1A1AA),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal: Source / Mirror selection
    if (showSources) {
        AlertDialog(
            onDismissRequest = { showSources = false },
            title = { Text("Streaming Servers / Mirrors") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sources.isEmpty()) {
                        item { Text("No additional mirrors found for this title.") }
                    }
                    items(sources, key = { it.id.value }) { source ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (source.id == ui.source?.id) Color(0xFFE11D48).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (source.id == ui.source?.id) Color(0xFFE11D48) else Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    if (owner.open(source) is Outcome.Success) {
                                        onSourceChanged(source)
                                    }
                                    showSources = false
                                }
                            }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    text = "${source.provider.uppercase()} (${source.kind})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = source.originalUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSources = false }) { Text("Close") }
            }
        )
    }

    // Modal: Stream Tracks & Quality
    if (showTracks) {
        AlertDialog(
            onDismissRequest = { showTracks = false },
            title = { Text("Stream Quality & Tracks") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(onClick = {
                            player?.let {
                                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .setMaxVideoSize(1920, 1080)
                                    .build()
                            }
                            showTracks = false
                        }) {
                            Text("Auto / Best Quality")
                        }
                    }
                    item {
                        TextButton(onClick = {
                            player?.let {
                                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                            }
                            showTracks = false
                        }) {
                            Text("Subtitles Off")
                        }
                    }
                    player?.currentTracks?.groups.orEmpty().forEach { group ->
                        items((0 until group.length).toList()) { index ->
                            val format = group.getTrackFormat(index)
                            val label = when (group.type) {
                                C.TRACK_TYPE_VIDEO -> "Video ${format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "Source"}"
                                C.TRACK_TYPE_AUDIO -> "Audio ${format.label ?: format.language ?: ""}"
                                else -> "Subtitle ${format.label ?: format.language ?: ""}"
                            }
                            TextButton(
                                enabled = group.isTrackSupported(index),
                                onClick = {
                                    owner.selectTrack(group, index)
                                    showTracks = false
                                }
                            ) {
                                Text("$label ${if (group.isTrackSelected(index)) "✓" else ""}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTracks = false }) { Text("Close") }
            }
        )
    }
}
