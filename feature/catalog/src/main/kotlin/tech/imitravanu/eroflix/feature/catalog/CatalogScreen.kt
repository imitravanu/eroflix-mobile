@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package tech.imitravanu.eroflix.feature.catalog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import tech.imitravanu.eroflix.core.ui.*
import tech.imitravanu.eroflix.model.*

val CatalogWindowEnd = SemanticsPropertyKey<Int>("CatalogWindowEnd")

@Composable fun CatalogScreen(model: BrowseViewModel, heading: String? = null, onTitle: (TitleId) -> Unit) {
    val query by model.query.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val density by model.density.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    fun open(value: String) { keyboard?.hide(); panel = value }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val densityInfo=LocalDensity.current
    val compact = maxHeight < 400.dp || WindowInsets.ime.getBottom(densityInfo)>0
    val compactIcons=maxWidth<360.dp || densityInfo.fontScale>1.3f
    Column(Modifier.fillMaxSize().imePadding()) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.width(if(compactIcons) 48.dp else 96.dp).heightIn(min = 48.dp).testTag("providers-button").semantics { contentDescription="Providers. Tap or hold to open.";role=Role.Button }.combinedClickable(
                    onClick = { open("Providers") }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open("Providers") }, onLongClickLabel = "Hold to open Providers")) { Box(Modifier.padding(12.dp),contentAlignment=Alignment.Center) { Text(if(compactIcons) "☰" else "Providers",Modifier.clearAndSetSemantics { }) } }
                OutlinedTextField(value = query, onValueChange = model::search, label = { Text("Search catalog") }, singleLine = true,
                    modifier = Modifier.weight(1f).padding(4.dp).testTag("catalog-search"))
                Surface(Modifier.width(if(compactIcons) 48.dp else 96.dp).heightIn(min = 48.dp).testTag("filters-button").semantics { contentDescription="Filters. Tap or hold to open.";role=Role.Button }.combinedClickable(
                    onClick = { open("Filters") }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open("Filters") }, onLongClickLabel = "Hold to open Filters")) { Box(Modifier.padding(12.dp),contentAlignment=Alignment.Center) {
                    if(compactIcons) androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                        val path=androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width*.1f,size.height*.15f);lineTo(size.width*.9f,size.height*.15f)
                            lineTo(size.width*.58f,size.height*.52f);lineTo(size.width*.58f,size.height*.9f)
                            lineTo(size.width*.42f,size.height*.78f);lineTo(size.width*.42f,size.height*.52f);close()
                        };drawPath(path,androidx.compose.ui.graphics.Color.White)
                    } else Text("Filters",Modifier.clearAndSetSemantics { })
                } }
            }
            heading?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { open("Providers") }, modifier = Modifier.testTag("providers-button").heightIn(min = 48.dp)) { Text("Providers") }
                TextButton(onClick = { open("Filters") }, modifier = Modifier.testTag("filters-button").heightIn(min = 48.dp)) { Text("Filters") }
            }
            heading?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge) }
            OutlinedTextField(value = query, onValueChange = model::search, label = { Text("Search catalog") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("catalog-search"))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { CatalogOptions(model, density) }
        }
        notice?.let { Text(it, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,maxLines=if(compact) 1 else Int.MAX_VALUE,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        when (val current = state) {
            BrowseState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading catalog results" }); Text("Loading catalog…", Modifier.padding(16.dp)) }
            is BrowseState.Failed -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { MessageState("Catalog unavailable", current.error.message, retry = model::retry) }
            is BrowseState.Ready -> {
                val metadata = current.metadata
                Text(buildString {
                    if (current.matches != null) append("${current.matches} matches · ")
                    append(if (metadata != null) "${metadata.titleCount} offline titles · ${metadata.generation.value}" else "Offline catalog · ${current.stamp?.generation?.value.orEmpty()}")
                }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("catalog-metadata"), style = MaterialTheme.typography.labelSmall, maxLines = if(compact) 1 else 3)
                if (current.titles.isEmpty()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        MessageState("No titles found", "These search and filter selections have no visible titles. Hidden titles are excluded.")
                        TextButton(onClick = { model.search(""); model.reset() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset search and filters") }
                    }
                } else CatalogGrid(current, model, density, onTitle, Modifier.weight(1f))
            }
        }
        // Deliberately inside content; no system-edge exclusion or edge interception.
        if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Providers", "Filters").forEach { side ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.heightIn(min = 48.dp).combinedClickable(
                        onClick = { open(side) }, onLongClickLabel = "Open $side with feedback",
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open(side) })
                        .semantics { contentDescription = "$side handle. Tap or hold to open." }) {
                    Text(if (side == "Providers") "› Providers" else "Filters ‹", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    panel?.let { active ->
        Dialog(onDismissRequest = { panel = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .55f)).safeDrawingPadding()) {
                Box(Modifier.fillMaxSize().clickable(onClickLabel = "Close $active") { panel = null })
                Surface(Modifier.align(if (active == "Providers") Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight().fillMaxWidth(.9f).widthIn(max = 420.dp), tonalElevation = 8.dp) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(active, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { panel = null }) { Text("Done") }
                        }
                        when (val current = state) {
                            BrowseState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                            is BrowseState.Failed -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { MessageState("Filters unavailable", current.error.message, retry = model::retry) }
                            is BrowseState.Ready -> {
                                current.facetError?.let { MessageState("Counts unavailable", it.message, retry = model::retry) }
                                val facets = current.facets
                                if (facets != null) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
                                    item { Text("OR within a group · AND across groups. Counts exclude hidden titles.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall) }
                                    notice?.let { item { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) } }
                                    item { TextButton(onClick = model::reset) { Text("Reset filters") } }
                                    if (compact) item { Row(Modifier.horizontalScroll(rememberScrollState())) { CatalogOptions(model, density) } }
                                    val groups = if (active == "Providers") listOf("provider" to facets.providers) else listOf("country" to facets.countries, "decade" to facets.decades, "studio" to facets.studios)
                                    groups.forEach { (key, options) ->
                                        item { Text(key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp)) }
                                        items(options, key = { "$key:${it.value}" }) { option ->
                                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleableFacet(option) { model.toggle(key, option.value) }, verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = option.selected, onCheckedChange = null)
                                                Text("${option.label} (${option.titleCount})${if (option.selected && option.titleCount == 0L) " · No matches, tap to remove" else ""}", Modifier.padding(start = 8.dp))
                                            }
                                        }
                                    }
                                    if (active == "Filters") {
                                        item { Text("Duration", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                                        if (!facets.knownDurationAvailable) item { Text("Known duration unavailable in this selection. Unknown metadata is preserved.", style = MaterialTheme.typography.bodySmall) }
                                        items(listOf("all" to "Any duration", "short" to "Under 30 minutes", "long" to "30 minutes or longer", "unknown" to "Unknown duration")) { (key, label) ->
                                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model.duration(key) }.semantics { role = Role.RadioButton; selected = model.durationSelection() == key }, verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(selected = model.durationSelection() == key, onClick = null)
                                                Text(label, Modifier.padding(start = 8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
@Composable private fun RowScope.CatalogOptions(model: BrowseViewModel, density: Int) {
    var sorting by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { sorting = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sort: ${model.request().sort.label()}") }
        DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
            CatalogSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label()) }, onClick = { model.sort(sort); sorting = false }) }
        }
    }
    TextButton(onClick = { model.density(if (density == 150) 110 else if (density == 110) 220 else 150) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Grid: ${if (density == 110) "Compact" else if (density == 220) "Large" else "Comfortable"}")
    }
    TextButton(onClick = model::top, modifier = Modifier.heightIn(min = 48.dp)) { Text("Top") }
}
private fun Modifier.toggleableFacet(option: FacetOption, action: () -> Unit) = clickable(onClick = action).semantics {
    role = Role.Checkbox; selected = option.selected; contentDescription = "${option.label}, ${option.titleCount} titles${if (option.selected) ", selected" else ""}"
}
private fun CatalogSort.label() = when (this) { CatalogSort.TITLE -> "Title"; CatalogSort.YEAR_NEWEST -> "Newest year"; CatalogSort.YEAR_OLDEST -> "Oldest year" }

@Composable private fun CatalogGrid(ready: BrowseState.Ready, model: BrowseViewModel, density: Int, onTitle: (TitleId) -> Unit, modifier: Modifier) {
    val scroll = rememberLazyGridState(model.anchorIndex(), model.anchorOffset())
    val latestReady by rememberUpdatedState(ready)
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) -> model.anchor(index, offset, latestReady.titles.getOrNull(index)?.id?.value) }
    }
    Box(modifier.testTag("catalog-applied-${ready.stamp?.queryKey}")) {
    LazyVerticalGrid(columns = GridCells.Adaptive(density.dp), state = scroll, modifier = Modifier.fillMaxSize().testTag("catalog-grid").semantics { this[CatalogWindowEnd] = ready.dropped + ready.titles.size },
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ready.titles, key = { it.id.value }) { title -> TitleCard(title, onTitle) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ready.appending) CircularProgressIndicator(Modifier.padding(16.dp).semantics { contentDescription = "Loading more titles" })
                ready.appendError?.let { Text(it.message); TextButton(onClick = model::append) { Text("Retry more titles") } }
                if (!ready.appending && ready.appendError == null && ready.next != null) {
                    OutlinedButton(onClick = model::append, modifier = Modifier.testTag("load-more").heightIn(min = 48.dp)) { Text("Load more titles") }
                }
                if (ready.next == null) Text("End of results", Modifier.padding(16.dp))
                if (ready.dropped > 0) Text("Showing a bounded window. Use Top to return to the beginning.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    }
}
@Composable fun TitleCard(title: TitleSummary, onTitle: (TitleId) -> Unit) {
    Card(onClick = { onTitle(title.id) }, modifier = Modifier.fillMaxWidth().testTag("title-${title.id.value}")
        .semantics { contentDescription = "${title.title}, ${title.year?.toString() ?: "year unknown"}. Open details" }) {
        CatalogPoster(title, Modifier.fillMaxWidth().aspectRatio(.75f))
        Column(Modifier.padding(12.dp)) {
            Text(title.title, style = MaterialTheme.typography.titleSmall)
            Text(title.year?.toString() ?: "Year unknown", style = MaterialTheme.typography.bodySmall)
        }
    }
}
