@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tech.imitravanu.eroflix

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val isPlayer = entry?.destination?.hasRoute<PlayerRoute>() == true
    val isMain = (0..3).any { tabSelected(it, entry?.destination) }
    val isBrowse = tabSelected(0, entry?.destination)
    Scaffold(contentWindowInsets=if(inPip || isPlayer) WindowInsets(0,0,0,0) else ScaffoldDefaults.contentWindowInsets, topBar = {
        if(!inPip && !isPlayer) Column {
            CenterAlignedTopAppBar(title = { Text("EROFLIX", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (!isMain) IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("‹") }
                })
            if (container.fixture) Text(stringResource(R.string.fixture_label),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }, bottomBar = {
        if (isMain && !inPip && !isPlayer) NavigationBar {
            val navItems = listOf(
                Triple(0, "Browse", "◇"),
                Triple(2, "Library", "♡"),
                Triple(3, "Settings", "⚙")
            )
            navItems.forEach { (index, label, symbol) ->
                NavigationBarItem(
                    selected = tabSelected(index, entry?.destination),
                    onClick = {
                        val route: Any = when (index) { 0 -> BrowseRoute; 2 -> LibraryRoute; else -> SettingsRoute }
                        nav.navigate(route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                    },
                    icon = { Text(symbol, Modifier.clearAndSetSemantics { }) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("tab-$index")
                )
            }
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = BrowseRoute, modifier = Modifier.padding(if (isPlayer) PaddingValues(0.dp) else padding).fillMaxSize()) {
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
    var selectedSourceIndex by remember(id) { mutableIntStateOf(0) }

    fun playSelectedSource(summary: TitleSummary, source: SourceHandle) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                actionMessage = when (val outcome = onPlayer(summary, source)) {
                    is Outcome.Failure -> outcome.error.message
                    is Outcome.Success -> "Player opened."
                }
            } finally { busy = false }
        }
    }

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
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFE11D48))
        }
        is Outcome.Failure -> MessageState("Title unavailable", current.error.message, retry = { retry++ })
        is Outcome.Success -> {
            val details = current.value
            val activeSource = details.sources.getOrNull(selectedSourceIndex) ?: details.sources.firstOrNull()

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.imePadding().fillMaxSize().testTag("title-details")
            ) {
                // 1. HERO POSTER & BASIC METADATA
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = Color(0xFF141418),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                            modifier = Modifier.width(120.dp).aspectRatio(0.72f)
                        ) {
                            CatalogPoster(details.summary, Modifier.fillMaxSize())
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = details.summary.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            // Year & Duration Chips
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                details.summary.year?.let { y ->
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                        color = Color(0xFF1E1E24)
                                    ) {
                                        Text(
                                            text = y.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFA1A1AA),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                details.summary.durationMs?.let { ms ->
                                    val mins = ms / 60_000
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                        color = Color(0xFF1E1E24)
                                    ) {
                                        Text(
                                            text = if (mins > 0) "$mins min" else "Short",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFA1A1AA),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                details.entities.firstOrNull { it.kind == EntityKind.COUNTRY }?.let { c ->
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                        color = Color(0xFF1E1E24)
                                    ) {
                                        Text(
                                            text = c.label.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFA1A1AA),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            // Favorite / Save Quick Action Chips
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                FilterChip(
                                    enabled = !busy,
                                    selected = personal.favorite,
                                    onClick = { action { container.personal.setFavorite(details.summary, !personal.favorite) } },
                                    label = { Text(if (personal.favorite) "♥ Favorited" else "♡ Favorite") },
                                    modifier = Modifier.heightIn(min = 40.dp)
                                )
                                FilterChip(
                                    enabled = !busy,
                                    selected = personal.saved,
                                    onClick = { action { container.personal.setSaved(details.summary, !personal.saved) } },
                                    label = { Text(if (personal.saved) "✓ Saved" else "🔖 Save") },
                                    modifier = Modifier.heightIn(min = 40.dp)
                                )
                            }
                        }
                    }
                }

                // 2. PRIMARY ACTION: PROMINENT WATCH NOW BUTTON
                item {
                    Button(
                        enabled = !busy && activeSource != null,
                        onClick = {
                            activeSource?.let { playSelectedSource(details.summary, it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            text = if (activeSource != null) "▶  Watch Now (${activeSource.provider.uppercase()})" else "▶  No Available Stream",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // 3. SERVER / MIRROR SWITCHER CHIPS
                if (details.sources.size > 1) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Streaming Server / Mirror",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA1A1AA)
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                details.sources.forEachIndexed { idx, src ->
                                    val isSelected = (idx == selectedSourceIndex)
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                        color = if (isSelected) Color(0xFFE11D48).copy(alpha = 0.2f) else Color(0xFF1E1E24),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) Color(0xFFE11D48) else Color(0xFF27272A)
                                        ),
                                        modifier = Modifier.clickable { selectedSourceIndex = idx }
                                    ) {
                                        Text(
                                            text = "Server ${idx + 1}: ${src.provider.uppercase()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color(0xFFE11D48) else Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                actionMessage?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            color = Color(0xFFF59E0B),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("source-action-result")
                        )
                    }
                }

                // 4. SYNOPSIS / DESCRIPTION
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = details.description?.takeIf { it.isNotBlank() } ?: "No description available for this title.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA1A1AA)
                        )
                    }
                }

                // 5. LINKED ENTITIES (Performers, Studios, Decades)
                if (details.entities.isNotEmpty() || details.summary.year != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cast & Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                details.entities.forEach { entity ->
                                    OutlinedButton(
                                        onClick = { onEntity(entity) },
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 36.dp).testTag("entity-${entity.kind}-${entity.key}")
                                    ) {
                                        Text(entity.label, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                details.summary.year?.let { year ->
                                    OutlinedButton(
                                        onClick = { onEntity(EntityRef(EntityKind.DECADE, (year / 10 * 10).toString(), "${year / 10 * 10}s")) },
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 36.dp)
                                    ) {
                                        Text("${year / 10 * 10}s Cinema", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. TECHNICAL SOURCE LIST (Retained with play- and download- test tags for automated tests)
                item {
                    Text("Technical Sources (${details.sources.size})", style = MaterialTheme.typography.titleSmall, color = Color(0xFFA1A1AA))
                }
                items(details.sources, key = { it.id.value }) { source ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = Color(0xFF141418),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${source.provider.uppercase()} · ${source.kind.name}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(source.provenance ?: "Verified", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA1A1AA))
                            }
                            Text(source.originalUrl, style = MaterialTheme.typography.bodySmall, color = Color(0xFF71717A), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = { playSelectedSource(details.summary, source) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("play-${source.id.value}")
                                ) {
                                    Text("Play Source")
                                }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { action { container.downloads.enqueue(DownloadIntent(details.summary, source, TrackSelection(emptySet()))) } },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("download-${source.id.value}")
                                ) {
                                    Text("Download")
                                }
                            }
                            actionMessage?.let {
                                Text(it, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("source-action-result-${source.id.value}"))
                            }
                        }
                    }
                }

                if (busy) {
                    item {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFFE11D48))
                    }
                }
            }
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
