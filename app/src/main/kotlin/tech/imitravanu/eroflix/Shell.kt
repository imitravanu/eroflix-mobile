@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tech.imitravanu.eroflix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.navigation.compose.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import tech.imitravanu.eroflix.core.ui.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.feature.catalog.*

@Serializable data object BrowseRoute
@Serializable data object DownloadsRoute
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute
@Serializable data object DiagnosticsRoute
@Serializable data class DetailsRoute(val titleId: String)
@Serializable data class EntityRoute(val kind: String, val key: String, val label: String)
@Serializable data class PlayerRoute(val titleId: String, val sourceId: String)

private fun tabSelected(index: Int, destination: NavDestination?) = when (index) {
    0 -> destination?.hasRoute<BrowseRoute>() == true
    1 -> destination?.hasRoute<DownloadsRoute>() == true
    2 -> destination?.hasRoute<LibraryRoute>() == true
    else -> destination?.hasRoute<SettingsRoute>() == true
}

@Composable fun EroflixShell(container: AppContainer, requestSubtitle: (() -> Unit)? = null,
    pendingSubtitle: android.net.Uri? = null, onSubtitleConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val app=LocalContext.current.applicationContext as EroflixApplication
    val playbackUi=(container.player as? tech.imitravanu.eroflix.core.media.ProductionPlayback)?.ui?.collectAsStateWithLifecycle()
    val inPip=playbackUi?.value?.inPip == true
    val entry by nav.currentBackStackEntryAsState()
    val isMain = (0..3).any { tabSelected(it, entry?.destination) }
    val isBrowse = tabSelected(0, entry?.destination)
    Scaffold(contentWindowInsets=if(inPip) WindowInsets(0,0,0,0) else ScaffoldDefaults.contentWindowInsets, topBar = {
        if(!inPip) Column {
            CenterAlignedTopAppBar(title = { Text("EROFLIX", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (!isMain) IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("‹") }

                })
            if (container.fixture) Text(stringResource(R.string.fixture_label),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }, bottomBar = {
        if (isMain && !inPip) NavigationBar {
            val names = listOf("Browse", "Downloads", "Library", "Settings")
            val symbols = listOf("◇", "↓", "♡", "⚙")
            names.forEachIndexed { index, label ->
                NavigationBarItem(selected = tabSelected(index, entry?.destination),
                    onClick = {
                        val route: Any = when (index) { 0 -> BrowseRoute; 1 -> DownloadsRoute; 2 -> LibraryRoute; else -> SettingsRoute }
                        nav.navigate(route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Text(symbols[index], Modifier.clearAndSetSemantics { }) }, label = { Text(label) }, modifier = Modifier.testTag("tab-$index"))
            }
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = BrowseRoute, modifier = Modifier.padding(padding).fillMaxSize()) {
            composable<BrowseRoute> { backStack ->
                val model: BrowseViewModel = viewModel(backStack, factory = viewModelFactory {
                    initializer { BrowseViewModel(container.catalog, container.personal, createSavedStateHandle(),gridPreference=app.gridDensity,persistGridPreference=app::setGridDensity) }
                })
                CatalogScreen(model) { nav.navigate(DetailsRoute(it.value)) }
            }
            composable<DownloadsRoute> {
                val downloads=container.downloads as? tech.imitravanu.eroflix.core.media.ProductionDownloads
                if(downloads==null) MessageState("Downloads unavailable","This isolated test container has no durable download owner.")
                else tech.imitravanu.eroflix.feature.downloads.DownloadsScreen(downloads,container.exports) { job -> nav.navigate(PlayerRoute(job.intent.title.id.value,job.intent.source.id.value)) }
            }
            composable<LibraryRoute> { LibraryScreen(container) { nav.navigate(DetailsRoute(it.value)) } }
            composable<SettingsRoute> { SettingsScreen(container) { nav.navigate(DiagnosticsRoute) } }
            composable<DiagnosticsRoute> { DiagnosticsScreen() }
            composable<DetailsRoute> { backStack ->
                val route = backStack.toRoute<DetailsRoute>()
                DetailsScreen(TitleId(route.titleId), container,
                    onEntity = { nav.navigate(EntityRoute(it.kind.name, it.key, it.label)) },
                    onPlayer = { title, source ->
                        when (val outcome = container.player.open(source)) {
                            is Outcome.Failure -> outcome
                            is Outcome.Success -> { nav.navigate(PlayerRoute(title.id.value, source.id.value)); outcome }
                        }
                    })
            }
            composable<EntityRoute> { backStack ->
                val route = backStack.toRoute<EntityRoute>()
                val entity = EntityRef(EntityKind.valueOf(route.kind), route.key, route.label)
                val model: BrowseViewModel = viewModel(backStack, factory = viewModelFactory {
                    initializer { BrowseViewModel(container.catalog, container.personal, createSavedStateHandle(), entity = entity,gridPreference=app.gridDensity,persistGridPreference=app::setGridDensity) }
                })
                CatalogScreen(model, route.label + if (entity.kind == EntityKind.SERIES) " · Title order (episode order unavailable)" else "") { nav.navigate(DetailsRoute(it.value)) }
            }
            composable<PlayerRoute> { playerEntry ->
                val route=playerEntry.toRoute<PlayerRoute>()
                val owner=container.player as tech.imitravanu.eroflix.core.media.ProductionPlayback
                val activity=androidx.activity.compose.LocalActivity.current as? MainActivity
                LaunchedEffect(route) { owner.restoreRoute(TitleId(route.titleId),SourceId(playerEntry.savedStateHandle.get<String>("selectedSourceId") ?: route.sourceId)) }
                tech.imitravanu.eroflix.feature.player.PlayerScreen(owner,container.catalog,container.downloads::enqueue,
                    onBack={ nav.popBackStack() },onPip={ activity?.enterPlayerPip() },
                    onSourceChanged={ playerEntry.savedStateHandle["selectedSourceId"]=it.id.value },
                    requestSubtitle=requestSubtitle,pendingSubtitle=pendingSubtitle,onSubtitleConsumed=onSubtitleConsumed)
            }
        }
    }
    (container.downloads as? tech.imitravanu.eroflix.core.media.ProductionDownloads)?.let { tech.imitravanu.eroflix.feature.downloads.DownloadSelectionDialog(it) }
}

@Composable private fun TitleGrid(titles: List<TitleSummary>, onTitle: (TitleId) -> Unit) {
    val scroll = rememberLazyGridState()
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = scroll, contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(titles, key = { it.id.value }) { title ->
            Card(onClick = { onTitle(title.id) }, modifier = Modifier.fillMaxWidth().testTag("title-${title.id.value}")) {
                PosterPlaceholder(title.title, Modifier.fillMaxWidth().aspectRatio(0.75f))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title.title, style = MaterialTheme.typography.titleSmall)
                    Text(title.year?.toString() ?: "Year unknown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun DetailsScreen(id: TitleId, container: AppContainer, onEntity: (EntityRef) -> Unit,
    onPlayer: suspend (TitleSummary, SourceHandle) -> Outcome<Unit>) {
    var result by remember(id) { mutableStateOf<Outcome<TitleDetails>?>(null) }
    val personal by remember(id) { container.personal.observeState(id) }.collectAsStateWithLifecycle(PersonalState())
    val generation by container.catalog.activeGeneration.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Outcome<*>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                actionMessage = when (val outcome = withContext(Dispatchers.IO) { block() }) {
                    is Outcome.Failure -> outcome.error.message
                    is Outcome.Success -> "Updated."
                }
            } finally { busy = false }
        }
    }
    LaunchedEffect(id, generation, retry) {
        result = null
        result = withContext(Dispatchers.IO) { container.catalog.details(id) }
    }
    when (val current = result) {
        null -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
        is Outcome.Failure -> MessageState("Title unavailable", current.error.message, retry = { retry++ })
        is Outcome.Success -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.imePadding().testTag("title-details")) {
            val details = current.value
            item { Text(details.summary.title, style = MaterialTheme.typography.headlineMedium) }
            item { CatalogPoster(details.summary, Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(.75f)) }
            item { Text("${details.summary.year?.toString() ?: "Year unknown"} · ${details.summary.durationMs?.let { "${it / 60_000} minutes" } ?: "Duration unknown"}") }
            item { Text(details.description?.takeIf { it.isNotBlank() } ?: "Description unavailable.") }
            item { Text("Provenance", style = MaterialTheme.typography.titleMedium); Text(details.provenance ?: "Provenance unavailable.") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(enabled = !busy, selected = personal.favorite, onClick = { action { container.personal.setFavorite(details.summary, !personal.favorite) } }, label = { Text("Favorite") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(enabled = !busy, selected = personal.saved, onClick = { action { container.personal.setSaved(details.summary, !personal.saved) } }, label = { Text("Save for later") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(enabled = !busy, selected = personal.hidden, onClick = { action { container.personal.setHidden(details.summary, !personal.hidden, System.currentTimeMillis()) } }, label = { Text(if (personal.hidden) "Unhide" else "Hide") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            actionMessage?.let { message -> item { Text(message, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("source-action-result")) } }
            if (details.entities.isEmpty()) item { Text("No linked performers, studios or series supplied.") }
            items(details.entities, key = { "${it.kind}:${it.key}" }) { entity -> TextButton(onClick = { onEntity(entity) }, modifier = Modifier.heightIn(min = 48.dp).testTag("entity-${entity.kind}-${entity.key}")) { Text(entity.label) } }
            details.summary.year?.let { year -> item {
                TextButton(onClick = { onEntity(EntityRef(EntityKind.DECADE, (year / 10 * 10).toString(), "${year / 10 * 10}s")) }) { Text("Browse ${year / 10 * 10}s") }
            } }
            item { Text("Sources (${details.sources.size})", style = MaterialTheme.typography.titleLarge) }
            if (details.sources.isEmpty()) item { Text("No source is supplied for this title.") }
            items(details.sources, key = { it.id.value }) { source ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${source.provider} · ${source.kind.name}", style = MaterialTheme.typography.titleMedium)
                    Text(source.originalUrl, style = MaterialTheme.typography.bodySmall)
                    source.externalId?.let { Text("Provider ID: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(source.provenance ?: "Source provenance unavailable.", style = MaterialTheme.typography.bodySmall)
                    Text("Availability is checked for this exact source. Download and export support can differ from playback.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            if (!busy) { busy = true; scope.launch {
                                try { actionMessage = when (val outcome = onPlayer(details.summary, source)) {
                                    is Outcome.Failure -> outcome.error.message
                                    is Outcome.Success -> "Player opened."
                                } } finally { busy = false }
                            } }
                        }, modifier = Modifier.testTag("play-${source.id.value}")) { Text("Play source") }
                        OutlinedButton(enabled = !busy, onClick = { action {
                            container.downloads.enqueue(DownloadIntent(details.summary, source, TrackSelection(emptySet())))
                        } }, modifier = Modifier.testTag("download-${source.id.value}")) { Text("Download source") }
                    }
                    actionMessage?.let { Text(it, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("source-action-result-${source.id.value}")) }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Applying action…") }
        }
    }
}

@Composable private fun LibraryScreen(container: AppContainer, onTitle: (TitleId) -> Unit) {
    val app=LocalContext.current.applicationContext as EroflixApplication
    val gridDensity by app.gridDensity.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(LibraryShelf.FAVORITES.name) }
    val shelf = LibraryShelf.valueOf(selected)
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val entries by remember(shelf,query,page) { container.personal.observeShelfPage(shelf,query,page*60,61) }.collectAsStateWithLifecycle(emptyList())
    val visibleEntries=entries.take(60)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUnhide by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Favorites" to LibraryShelf.FAVORITES, "Saved" to LibraryShelf.SAVED, "History" to LibraryShelf.HISTORY, "Continue" to LibraryShelf.CONTINUE_WATCHING, "Hidden" to LibraryShelf.HIDDEN).forEach { (label, value) ->
                FilterChip(selected = shelf == value, onClick = { selected = value.name; page=0 }, label = { Text(label) })
            }
        }
        OutlinedTextField(value=query, onValueChange={ query=it; page=0 }, label={ Text("Search library") },
            singleLine=true, modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp), horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={ page-- }, enabled=page>0) { Text("Previous") }
            Text("Page ${page+1}", Modifier.padding(12.dp))
            TextButton(onClick={ page++ }, enabled=entries.size>60) { Text("Next") }
        }
        error?.let { Text(it, Modifier.padding(16.dp), color=MaterialTheme.colorScheme.error) }
        if (visibleEntries.isEmpty()) MessageState("Your library", "No titles on this page.")
        else if (shelf == LibraryShelf.HIDDEN) {
            LazyColumn(contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item { OutlinedButton(onClick={ confirmUnhide=true }) { Text("Unhide all") } }
                items(visibleEntries, key={ it.title.id.value }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(entry.title.title, Modifier.weight(1f).padding(vertical=12.dp))
                        OutlinedButton(onClick={ scope.launch {
                            when (val result=container.personal.setHidden(entry.title, false, System.currentTimeMillis())) {
                                is Outcome.Failure -> error=result.error.message
                                is Outcome.Success -> error=null
                            }
                        } }) { Text("Unhide") }
                    }
                }
            }
        }
        else LazyVerticalGrid(columns=GridCells.Adaptive(gridDensity.dp), contentPadding=PaddingValues(16.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(visibleEntries, key={ it.title.id.value }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        PosterPlaceholder(entry.title.title, Modifier.fillMaxWidth().aspectRatio(0.75f))
                        TextButton(onClick={ onTitle(entry.title.id) }) { Text(entry.title.title) }
                        entry.title.year?.let { Text(it.toString(), style=MaterialTheme.typography.bodySmall) }
                        if (shelf==LibraryShelf.FAVORITES || shelf==LibraryShelf.SAVED) {
                            TextButton(onClick={ scope.launch {
                                val result=if (shelf==LibraryShelf.FAVORITES) container.personal.setFavorite(entry.title,false)
                                    else container.personal.setSaved(entry.title,false)
                                error=if (result is Outcome.Failure) result.error.message else null
                            } }) { Text(if (shelf==LibraryShelf.FAVORITES) "Remove favorite" else "Remove saved") }
                        }
                    }
                }
            }
        }
    }
    if (confirmUnhide) AlertDialog(
        onDismissRequest={ confirmUnhide=false },
        title={ Text("Unhide all titles?") },
        text={ Text("All hidden titles will be visible again. Favorites and progress stay unchanged.") },
        confirmButton={ TextButton(onClick={ confirmUnhide=false; scope.launch {
            when (val result=container.personal.unhideAll()) {
                is Outcome.Failure -> error=result.error.message
                is Outcome.Success -> error=null
            }
        } }) { Text("Unhide all") } },
        dismissButton={ TextButton(onClick={ confirmUnhide=false }) { Text("Cancel") } }
    )
}

@Composable private fun SettingsScreen(container: AppContainer, onDiagnostics: () -> Unit) {
    val context=LocalContext.current
    val downloads=container.downloads as? tech.imitravanu.eroflix.core.media.ProductionDownloads
    val player=container.player as? tech.imitravanu.eroflix.core.media.ProductionPlayback
    val autoHide by container.personal.observeAutoHide().collectAsStateWithLifecycle(false)
    val scope=rememberCoroutineScope()
    var settingsError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        val app=context.applicationContext as EroflixApplication
        val gridDensity by app.gridDensity.collectAsStateWithLifecycle()
        Text("Grid density",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(110 to "Compact",150 to "Comfortable",220 to "Large").forEach { (value,label) ->
                FilterChip(selected=gridDensity==value,onClick={ app.setGridDensity(value) },label={ Text(label) })
            }
        }
        Text("Screen capture protection is active.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Hide titles after watching over 90%", Modifier.weight(1f))
            Switch(checked=autoHide, onCheckedChange={ enabled -> scope.launch {
                val result=container.personal.setAutoHide(enabled)
                settingsError=if(result is Outcome.Failure) result.error.message else null
            } }, modifier=Modifier.semantics { contentDescription="Automatically hide watched titles" })
        }
        settingsError?.let { Text(it, color=MaterialTheme.colorScheme.error) }
        if (player != null) {
            val maxHeight by player.preferredMaxHeight.collectAsStateWithLifecycle()
            val backgroundAudio by player.defaultBackgroundAudio.collectAsStateWithLifecycle()
            Text("Preferred video quality", style=MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(480,720,1080,2160).forEach { height ->
                    FilterChip(selected=maxHeight==height, onClick={ player.setPreferredMaxHeight(height) }, label={ Text("${height}p") })
                }
            }
            Text("The player may use another quality when a source or manual track selection requires it.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Allow background audio by default", Modifier.weight(1f))
                Switch(checked=backgroundAudio,onCheckedChange=player::setDefaultBackgroundAudio,
                    modifier=Modifier.semantics { contentDescription="Allow background audio by default" })
            }
        }
        if (downloads != null) {
            val wifi by downloads.wifiOnly.collectAsStateWithLifecycle()
            val parallel by downloads.parallel.collectAsStateWithLifecycle()
            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Wi-Fi only")
                Switch(checked=wifi, onCheckedChange=downloads::setWifiOnly,
                    modifier=Modifier.semantics { contentDescription="Wi-Fi only downloads" })
            }
            Text("Concurrent downloads: $parallel (one while playing)")
            Slider(value=parallel.toFloat(), onValueChange={ downloads.setParallel(it.toInt()) },
                valueRange=1f..4f, steps=2,
                modifier=Modifier.semantics { contentDescription="Concurrent downloads" })
        }
        PrivacySettings((context.applicationContext as EroflixApplication).privacy)
        Text("Updates are not configured for this build.")
        OutlinedButton(onClick = onDiagnostics) { Text("Device diagnostics") }
        if(BuildConfig.DEBUG) OutlinedButton(onClick = { sourceLabIntent(context)?.let { (context.applicationContext as EroflixApplication).playback.stop(); context.startActivity(it) } }) { Text("Source lab") }
    }
}

@Composable private fun DiagnosticsScreen() {
    val context = LocalContext.current.applicationContext
    var result by remember { mutableStateOf<DeviceDiagnostics?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { result = withContext(Dispatchers.IO) { readDeviceDiagnostics(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val measured = result
    when {
        failed -> MessageState("Diagnostics unavailable", "Return to Settings and try again.")
        measured == null -> CircularProgressIndicator(Modifier.padding(24.dp))
        else -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Device diagnostics", style = MaterialTheme.typography.headlineMedium) }
            item { Text("${measured.model} · Android ${measured.androidRelease} · API ${measured.api}") }
            item { Text("Memory page size: ${measured.pageSizeBytes} bytes") }
            item { Text("Available storage: ${measured.availableStorageBytes / (1024 * 1024 * 1024)} GiB") }
            item { Text(measured.biometricStatus) }
            item { Text("Video decoders", style = MaterialTheme.typography.titleMedium) }
            items(measured.videoDecoders) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
