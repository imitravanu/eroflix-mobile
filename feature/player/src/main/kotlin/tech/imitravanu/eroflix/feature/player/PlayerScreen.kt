@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package tech.imitravanu.eroflix.feature.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.media3.common.*
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.core.network.*
import tech.imitravanu.eroflix.model.*

val PlayerCropMode=SemanticsPropertyKey<Boolean>("PlayerCropMode")

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable fun PlayerScreen(owner: ProductionPlayback, catalog: CatalogRepository,
    onDownload: suspend (DownloadIntent)->Outcome<JobId>, onBack: ()->Unit, onPip: ()->Unit,
    onSourceChanged: (SourceHandle)->Unit = {},
    onDetectedDownload: suspend (DownloadIntent,DetectedStream)->Outcome<JobId> = { _,_ -> Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"Stream downloads unavailable.")) },
    requestSubtitle: (() -> Unit)? = null, pendingSubtitle: Uri? = null, onSubtitleConsumed: () -> Unit = {}) {
    val ui by owner.ui.collectAsStateWithLifecycle()
    val player by owner.surfacePlayer.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val activity=androidx.activity.compose.LocalActivity.current ?: return
    val lifecycle=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    var locked by rememberSaveable { mutableStateOf(false) }
    var crop by rememberSaveable { mutableStateOf(false) }
    var tracks by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sources by remember(ui.title?.id) { mutableStateOf<List<SourceHandle>>(emptyList()) }
    var webEpoch by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<ContainedWebPlayer?>(null) }
    val originalBrightness=remember { activity.window.attributes.screenBrightness }
    val originalOrientation=remember { activity.requestedOrientation }
    val gestureLock=rememberUpdatedState(locked)
    val disallow=remember { RequestDisallowInterceptTouchEvent() }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val gestures=remember(owner,activity) { PlayerGestureRouter(activity,owner,{ gestureLock.value },{ crop=it },disallow::invoke) }
    fun acceptSubtitle(uri: Uri?, onComplete: () -> Unit = {}) {
        if(uri!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch(Dispatchers.IO) {
                var detectedMime=MimeTypes.APPLICATION_SUBRIP
                val valid=runCatching { requireNotNull(context.contentResolver.openInputStream(uri)) { "Subtitle could not be opened." }.use { input ->
                    val bytes=input.readNBytes(1024*1024+1)
                    require(bytes.size<=1024*1024) { "Subtitle exceeds 1 MiB." }
                    val text=bytes.toString(Charsets.UTF_8)
                    detectedMime=if(text.trimStart().startsWith("WEBVTT")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                    require(Regex("[0-9]{2}:[0-9]{2}(?::[0-9]{2})?[.,][0-9]{3}\\s+-->\\s+[0-9]{2}:[0-9]{2}").containsMatchIn(text)) { "No valid subtitle timestamps found. Choose SRT or WebVTT." }
                } }.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    if(valid!=null) message=valid.message ?: "Subtitle could not be read."
                    else {
                        val mime=detectedMime
                        message=when(val result=owner.subtitle(uri,mime)) {
                            is Outcome.Failure -> result.error.message
                            is Outcome.Success -> "External subtitle loaded."
                        }
                    }
                    onComplete()
                }
            }
        }
    }
    val subtitle=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> acceptSubtitle(uri) }
    LaunchedEffect(pendingSubtitle) {
        if(pendingSubtitle != null) acceptSubtitle(pendingSubtitle,onSubtitleConsumed)
    }
    DisposableEffect(owner,lifecycle) {
        owner.closeWeb={ web?.close();web=null }
        owner.onLockUi={ if(activity.isInPictureInPictureMode) activity.finish() }
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP) {
                gestures.cancel()
                owner.visibility(false,activity.isInPictureInPictureMode)
                activity.window.attributes=activity.window.attributes.apply { screenBrightness=originalBrightness }
            }
            if(event==Lifecycle.Event.ON_START) owner.visibility(true,activity.isInPictureInPictureMode)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            gestures.cancel()
            lifecycle.lifecycle.removeObserver(observer);web?.close();web=null;owner.closeWeb=null;owner.onLockUi=null
            owner.leaveRoute()
            activity.window.attributes=activity.window.attributes.apply { screenBrightness=originalBrightness }
            activity.requestedOrientation=originalOrientation
        }
    }
    DisposableEffect(player) {
        val listener=object: Player.Listener { override fun onTracksChanged(value: Tracks) { tracks++ } }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(ui.title?.id) {
        ui.title?.let { title -> sources=(withContext(Dispatchers.IO) { catalog.details(title.id) } as? Outcome.Success)?.value?.sources.orEmpty() }
    }
    if(ui.locked) { Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Playback locked");Button(onClick=onBack) { Text("Return to app") } };return }
    // Effects above remain at the same composition position when PiP swaps presentation.
    if(ui.inPip) {
        AndroidView(factory={ PlayerView(it).apply { useController=false } },update={ it.player=player;it.resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT },
            onRelease={ it.player=null },modifier=Modifier.fillMaxSize().testTag("pip-video-surface").semantics { contentDescription="Picture in picture video" })
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Text(ui.title?.title ?: "Player", Modifier.padding(12.dp), style=MaterialTheme.typography.titleLarge)
        if(ui.phase==PlaybackPhase.IDLE) {
            Text("Playback does not restart automatically after process restoration. Select Retry or return to the title.",Modifier.padding(12.dp))
            Button(onClick=onBack) { Text("Return to title") }
        }
        if(ui.web!=null) {
            val resolution=ui.web!!
            val policy=remember(resolution.entryUri) { owner.webPolicy(resolution) }
            if(policy==null) Text("The selected web source policy is unavailable.")
            else key(resolution.entryUri,webEpoch) {
                AndroidView(factory={ ctx -> ContainedWebPlayer(ctx,policy,owner::pause,onBack,{ message=it.message },localAsset=owner.webResourceOverride,onDownloadStream={ stream ->
                    val title=ui.title;val source=ui.source
                    if(title!=null && source!=null) scope.launch {
                        val result=onDetectedDownload(DownloadIntent(title,source,TrackSelection(emptySet())),stream)
                        if(result is Outcome.Failure && result.error.code!=ErrorCode.CANCELED) message=result.error.message
                    }
                }).also { web=it } },
                    modifier=Modifier.fillMaxWidth().height(420.dp),onRelease={ it.close();if(web===it) web=null })
            }
            Text(resolution.unavailableExplanation,Modifier.padding(12.dp))
        } else {
            Box(Modifier.testTag("native-player-surface").semantics { contentDescription="Native video surface";this[PlayerCropMode]=crop }.fillMaxWidth().heightIn(min=200.dp,max=420.dp).aspectRatio(16f/9f).onSizeChanged { surfaceSize=it }) {
                AndroidView(factory={ PlayerView(it).apply { useController=false;setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update={ it.player=player;it.resizeMode=if(crop) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    onRelease={ it.player=null },modifier=Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().pointerInteropFilter(requestDisallowInterceptTouchEvent=disallow) { event -> gestures.touch(event,surfaceSize.width.toFloat(),surfaceSize.height.toFloat()) })
            }
            Text("${ui.phase.name.lowercase()} · ${ui.positionMs/1000}s / ${ui.durationMs?.let { "${it/1000}s" } ?: "unknown/live"}",Modifier.padding(12.dp).semantics { liveRegion=LiveRegionMode.Polite })
            if(ui.phase in listOf(PlaybackPhase.RESOLVING,PlaybackPhase.BUFFERING)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!locked) {
                ui.durationMs?.let { duration -> Slider(value=ui.positionMs.toFloat().coerceIn(0f,duration.toFloat()),onValueChange={ owner.seekTo(it.toLong()) },valueRange=0f..duration.toFloat(),modifier=Modifier.semantics { contentDescription="Playback position" }) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={ if(ui.phase==PlaybackPhase.PLAYING) owner.pause() else owner.play() }) { Text(if(ui.phase==PlaybackPhase.PLAYING) "Pause" else "Play") }
                    OutlinedButton(onClick={ owner.seekTo(ui.positionMs-10_000) }) { Text("Back 10s") }
                    OutlinedButton(onClick={ owner.seekTo(ui.positionMs+10_000) }) { Text("Forward 10s") }
                    OutlinedButton(onClick={ owner.setSpeed(if(ui.speed<2) ui.speed+.25f else .5f) }) { Text("Speed ${ui.speed}×") }
                    OutlinedButton(onClick={ showTracks=true }) { Text("Quality / audio / subtitles") }
                    OutlinedButton(onClick={ scope.launch {
                        val result=owner.replayFromStart()
                        if(result is Outcome.Failure) message=result.error.message
                    } }) { Text("Replay from start") }
                    OutlinedButton(onClick={ if(requestSubtitle != null) requestSubtitle() else subtitle.launch(arrayOf("text/*","application/x-subrip")) }) { Text("Open SRT / VTT") }
                    OutlinedButton(onClick={ crop=!crop }) { Text(if(crop) "Crop" else "Fit") }
                    OutlinedButton(onClick={ activity.requestedOrientation=if(activity.resources.configuration.orientation==2) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) { Text("Rotate") }
                    OutlinedButton(onClick=onPip) { Text("Picture in picture") }
                }
                Text("Software gain ${ui.gain}× · PCM soft limiting; amplification may change dynamics.",Modifier.padding(12.dp))
                Slider(value=ui.gain,onValueChange=owner::gain,valueRange=1f..2f,modifier=Modifier.semantics { contentDescription="Software audio gain" })
                Row { Checkbox(checked=ui.backgroundAudio,onCheckedChange=owner::backgroundAudio);Text("Allow background audio this session",Modifier.padding(12.dp)) }
            }
        }
        ui.error?.let { Text(it.message,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error) }
        ui.message?.let { Text(it,Modifier.padding(12.dp)) }
        message?.let { Text(it,Modifier.padding(12.dp).semantics { liveRegion=LiveRegionMode.Polite }) }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(12.dp)) {
            Button(onClick={ locked=!locked }) { Text(if(locked) "Unlock controls" else "Lock controls") }
            if(!locked) {
                OutlinedButton(enabled=ui.source!=null,onClick={ scope.launch { owner.retry();webEpoch++ } }) { Text("Retry source") }
                OutlinedButton(onClick={ showSources=true }) { Text("Choose source") }
                if(ui.web==null) OutlinedButton(onClick={ scope.launch {
                    val source=ui.source;val title=ui.title
                    if(source!=null && title!=null) message=when(val result=onDownload(DownloadIntent(title,source,TrackSelection(player?.currentTracks?.groups.orEmpty().flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.mapNotNull { group.getTrackFormat(it).id } }.toSet())))) {
                        is Outcome.Failure -> result.error.message
                        is Outcome.Success -> "Download request accepted."
                    }
                } }) { Text("Download selected source") }
                OutlinedButton(onClick={ owner.stop();onBack() }) { Text("Stop and return") }
            }
        }
    }
    if(showSources) AlertDialog(onDismissRequest={ showSources=false },title={ Text("Sources · changing may restart playback") },text={
        LazyColumn {
            if(sources.isEmpty()) item { Text("No sources are available in the current title snapshot.") }
            items(sources,key={ it.id.value }) { source -> TextButton(onClick={ scope.launch { if(owner.open(source) is Outcome.Success) onSourceChanged(source);showSources=false } }) { Text("${source.provider} · ${source.kind}") } } }
    },confirmButton={ TextButton(onClick={ showSources=false }) { Text("Close") } })
    if(showTracks) AlertDialog(onDismissRequest={ showTracks=false },title={ Text("Actual available tracks") },text={
        val ignored=tracks
        LazyColumn {
            item { TextButton(onClick={ player?.let { it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setMaxVideoSize(1920,1080).build() } }) { Text("Auto / Source quality") } }
            item { TextButton(onClick={ player?.let { it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build() } }) { Text("Subtitles off") } }
            player?.currentTracks?.groups.orEmpty().forEach { group ->
                items((0 until group.length).toList()) { index ->
                    val format=group.getTrackFormat(index)
                    TextButton(enabled=group.isTrackSupported(index),onClick={ owner.selectTrack(group,index) }) { Text("${when(group.type) { C.TRACK_TYPE_VIDEO -> "Video ${format.height.takeIf { it>0 }?.let { "${it}p" } ?: "Source"}";C.TRACK_TYPE_AUDIO -> "Audio";else -> "Subtitle" }} ${format.label ?: format.language ?: format.sampleMimeType.orEmpty()}${if(group.isTrackSelected(index)) " · selected" else ""}") }
                }
            }
        }
    },confirmButton={ TextButton(onClick={ showTracks=false }) { Text("Close") } })
}
