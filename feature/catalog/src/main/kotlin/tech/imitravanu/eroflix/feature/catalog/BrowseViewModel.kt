package tech.imitravanu.eroflix.feature.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.*

sealed interface BrowseState {
    data object Loading : BrowseState
    data class Ready(val titles: List<TitleSummary>, val next: PageCursor? = null,
        val stamp: QueryStamp? = null, val facets: CatalogFacets? = null,
        val metadata: CatalogMetadata? = null, val appending: Boolean = false,
        val appendError: AppError? = null, val facetError: AppError? = null,
        val dropped: Int = 0, val matches: Long? = null) : BrowseState
    data class Failed(val error: AppError) : BrowseState
}
/** Each back stack entry owns one model. Only primitives are saved into Android state. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(private val repository: CatalogRepository, private val personal: PersonalRepository,
    private val savedState: SavedStateHandle, private val queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val entity: EntityRef? = null, private val gridPreference: MutableStateFlow<Int>? = null,
    private val persistGridPreference: (Int)->Unit = {}) : ViewModel() {
    val query = savedState.getStateFlow("search", "")
    val density: StateFlow<Int> = gridPreference ?: savedState.getStateFlow("density", 150)
    val notice = savedState.getStateFlow<String?>("notice", null)
    private val edits = MutableStateFlow(0)
    private val mutable = MutableStateFlow<BrowseState>(BrowseState.Loading)
    private val observing=MutableStateFlow(false)
    val state: StateFlow<BrowseState> = flow {
        mutable.value=BrowseState.Loading
        observing.value=true
        try { emitAll(mutable) }
        finally {
            observing.value=false
            appendJob?.cancel()
            mutable.value=BrowseState.Loading
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),BrowseState.Loading)
    private var appendJob: Job? = null
    private var revision: Long = -1
    private var serial = 0L
    private var activeRequest = CatalogQuery(entity = entity)
    private val boundaries = mutableListOf<Pair<Int, PageCursor>>()
    private fun restoreCursor(stamp: QueryStamp): PageCursor? {
        val fields = savedState.get<ArrayList<String>>("window") ?: return null
        if (fields.size != 5 || fields[0] != stamp.queryKey || fields[1] != stamp.generation.value) return null
        // Personal-only invalidation can reuse the immutable keyset location with a fresh revision.
        return PageCursor(stamp, fields[3], TitleId(fields[4]))
    }
    private fun saveCursor(cursor: PageCursor?) {
        if (cursor == null) savedState.remove<Any>("window")
        else savedState["window"] = arrayListOf(cursor.stamp.queryKey, cursor.stamp.generation.value,
            cursor.stamp.personalRevision.toString(), cursor.lastSortValue, cursor.lastTitleId.value)
    }
    private fun values(group: String): Set<FacetValue> = savedState.get<ArrayList<String>>(group).orEmpty().map {
        if (it == "u") FacetValue.Unknown else FacetValue.Known(it.removePrefix("k"))
    }.toSet()
    fun request() = CatalogQuery(search = query.value, providers = values("provider"), countries = values("country"),
        decades = values("decade"), studios = values("studio"), entity = entity,
        sort = CatalogSort.valueOf(savedState.get<String>("sort") ?: CatalogSort.TITLE.name),
        duration = when (savedState.get<String>("duration")) {
            "short" -> DurationFilter(maxMs = 30*60_000 - 1, includeUnknown = false)
            "long" -> DurationFilter(minMs = 30*60_000, includeUnknown = false)
            "unknown" -> DurationFilter(onlyUnknown = true)
            else -> DurationFilter()
        })
    init {
        viewModelScope.launch {
            observing.collectLatest { active ->
                if(!active) {
                    serial++
                    appendJob?.cancelAndJoin()
                    mutable.value=BrowseState.Loading
                    return@collectLatest
                }
            combine(query, repository.activeGeneration, personal.revision, edits) { text, generation, rev, _ ->
                Triple(text, generation, rev)
            }.collectLatest { (text, generation, rev) ->
                serial++; val token = serial
                appendJob?.cancel(); mutable.value = BrowseState.Loading
                val searchChanged = text != savedState.get<String>("appliedSearch")
                var q = request()
                val oldKey = savedState.get<String>("appliedKey")
                val changed = oldKey != null && oldKey != q.key()
                val oldGeneration = savedState.get<String>("appliedGeneration")
                if (changed || (oldGeneration != null && oldGeneration != generation?.value)) {
                    anchor(0, 0); savedState.remove<Any>("anchorId"); saveCursor(null)
                }
                revision = rev
                activeRequest = q
                boundaries.clear()
                if (searchChanged) delay(250)
                if (savedState.get<Boolean>("pendingProvider") == true && generation != null) {
                    val base = q.copy(countries = emptySet(), decades = emptySet(), studios = emptySet(), duration = DurationFilter())
                    val compatible = withContext(queryDispatcher) { repository.facets(base) }
                    if (compatible is Outcome.Success && compatible.value.stamp == QueryStamp(base.key(), generation, rev)) {
                        val cleared = mutableListOf<String>()
                        for ((group, options) in listOf("country" to compatible.value.countries, "decade" to compatible.value.decades, "studio" to compatible.value.studios)) {
                            val selected = values(group)
                            val retained = selected.filter { selectedValue -> options.any { it.value == selectedValue && it.titleCount > 0 } }.toSet()
                            if (retained != selected) {
                                savedState[group] = ArrayList(retained.map { if(it == FacetValue.Unknown) "u" else "k${(it as FacetValue.Known).key}" })
                                cleared.add("${selected.size - retained.size} $group selection(s)")
                            }
                        }
                        if (q.duration != DurationFilter()) {
                            val durationQuery = base.copy(duration = q.duration)
                            val count = withContext(queryDispatcher) { repository.count(durationQuery) }
                            if (count is Outcome.Success && count.value.stamp == QueryStamp(durationQuery.key(), generation, rev) && count.value.titleCount == 0L) {
                                savedState.remove<Any>("duration"); cleared.add("duration")
                            }
                        }
                        savedState["notice"] = if(cleared.isEmpty()) "Provider selection updated. Compatible filters retained."
                            else "Provider selection cleared ${cleared.joinToString(", ")} unavailable in this provider/search scope. Other filters were retained."
                        q = request(); activeRequest = q
                        savedState["pendingProvider"] = false
                    }
                    // On failure retain all filters; selected zero values remain removable.
                }
                savedState["appliedSearch"] = text
                savedState["appliedKey"] = q.key()
                savedState["appliedGeneration"] = generation?.value
                if (generation == null) { mutable.value = BrowseState.Failed(AppError(ErrorCode.MISSING_CATALOG, "Catalog is being prepared. Retry shortly.", true)); return@collectLatest }
                val stamp = QueryStamp(q.key(), generation, rev)
                val startingCursor = restoreCursor(stamp)
                saveCursor(startingCursor)
                val anchor = savedState.get<Int>("anchor") ?: 0
                val anchorId = savedState.get<String>("anchorId")
                withContext(Dispatchers.Main.immediate) {
                    val page = withContext(queryDispatcher) { repository.query(q.copy(cursor = startingCursor)) }
                    currentCoroutineContext().ensureActive()
                    if (!current(token, stamp)) return@withContext
                    when (page) {
                        is Outcome.Failure -> mutable.value = BrowseState.Failed(page.error)
                        is Outcome.Success -> {
                            if (page.value.stamp != stamp) return@withContext stale()
                            // Results are withheld until facets/count have the same revision boundary.
                            val facets = withContext(queryDispatcher) { repository.facets(q) }
                            val metadata = withContext(queryDispatcher) { repository.metadata() }
                            val count = withContext(queryDispatcher) { repository.count(q) }
                            if (!current(token, stamp)) return@withContext
                            if (facets is Outcome.Success && facets.value.stamp != stamp) return@withContext stale()
                            if (count is Outcome.Success && count.value.stamp != stamp) return@withContext stale()
                            var restored = page.value.items
                            var next = page.value.next
                            var appendError: AppError? = null
                            next?.let { boundaries.add(restored.size to it) }
                            while (next != null && restored.size < MAX_ITEMS &&
                                (restored.size <= anchor || (anchorId != null && restored.none { it.id.value == anchorId }))) {
                                when (val more = withContext(queryDispatcher) { repository.query(q.copy(cursor = next)) }) {
                                    is Outcome.Failure -> { appendError = more.error; break }
                                    is Outcome.Success -> {
                                        currentCoroutineContext().ensureActive()
                                        if (!current(token, stamp)) return@withContext
                                        if (more.value.stamp != stamp) return@withContext stale()
                                        val merged = (restored + more.value.items).distinctBy { it.id }
                                        if (merged.size == restored.size) { appendError = AppError(ErrorCode.INVALID_QUERY, "Paging made no progress. Retry.", true); break }
                                        restored = merged; next = more.value.next
                                        next?.let { boundaries.add(restored.size to it) }
                                    }
                                }
                            }
                            anchorId?.let { id -> restored.indexOfFirst { it.id.value == id }.takeIf { it >= 0 }?.let { savedState["anchor"] = it } }
                            mutable.value = BrowseState.Ready(restored, next, stamp,
                                (facets as? Outcome.Success)?.value, (metadata as? Outcome.Success)?.value?.takeIf { it.generation == generation },
                                appendError = appendError, facetError = (facets as? Outcome.Failure)?.error,
                                matches = (count as? Outcome.Success)?.value?.takeIf { it.stamp == stamp }?.titleCount)

                        }
                    }
                }
            }
            }
        }
    }
    private fun current(token: Long, stamp: QueryStamp) = observing.value && token == serial && repository.activeGeneration.value == stamp.generation && revision == stamp.personalRevision && activeRequest.key() == stamp.queryKey
    private fun stale() { mutable.value = BrowseState.Failed(AppError(ErrorCode.INVALID_QUERY, "The catalog changed while loading. Retry.", true)) }
    fun search(text: String) { savedState["search"] = text.take(256) }
    fun retry() { edits.value++ }
    fun top() { anchor(0, 0); savedState.remove<Any>("anchorId"); saveCursor(null); edits.value++ }
    fun sort(value: CatalogSort) { savedState["sort"] = value.name; edits.value++ }
    fun density(value: Int) {
        val selected=value.coerceIn(110,220)
        savedState["density"] = selected
        if(gridPreference!=null) { gridPreference.value=selected;persistGridPreference(selected) }
    }
    fun duration(value: String) { savedState["duration"] = value; edits.value++ }
    fun durationSelection() = savedState.get<String>("duration") ?: "all"
    fun dismissNotice() { savedState["notice"] = null }
    fun toggle(group: String, value: FacetValue) {
        if (group == "provider") savedState["pendingProvider"] = true
        val selected = values(group).toMutableSet()
        if (!selected.remove(value)) { if (selected.size >= 100) return; selected.add(value) }
        savedState[group] = ArrayList(selected.map { if (it == FacetValue.Unknown) "u" else "k${(it as FacetValue.Known).key}" }); edits.value++
    }
    fun reset() {
        listOf("provider", "country", "decade", "studio", "duration").forEach { savedState.remove<Any>(it) }
        edits.value++
    }
    fun anchor(index: Int, offset: Int, titleId: String? = null) {
        savedState["anchor"] = index; savedState["offset"] = offset
        if (titleId != null) savedState["anchorId"] = titleId
    }
    fun anchorIndex() = savedState.get<Int>("anchor") ?: 0
    fun anchorOffset() = savedState.get<Int>("offset") ?: 0
    fun append() {
        if(!observing.value) return
        val ready = mutable.value as? BrowseState.Ready ?: return
        val stamp = ready.stamp ?: return
        if (ready.next == null || appendJob?.isActive == true) return
        val token = serial
        val request = activeRequest
        appendJob = viewModelScope.launch { appendPage(token, stamp, request) }
    }
    private suspend fun appendPage(token: Long, stamp: QueryStamp, request: CatalogQuery) {
        val before = mutable.value as? BrowseState.Ready ?: return
        val cursor = before.next ?: return
        mutable.value = before.copy(appending = true, appendError = null)
        val result = withContext(queryDispatcher) { repository.query(request.copy(cursor = cursor)) }
        currentCoroutineContext().ensureActive()
        if (!current(token, stamp)) return
        when (result) {
            is Outcome.Failure -> mutable.value = before.copy(appendError = result.error)
            is Outcome.Success -> {
                if (result.value.stamp != stamp) { stale(); return }
                val merged = (before.titles + result.value.items).distinctBy { it.id }
                if (merged.size == before.titles.size) {
                    mutable.value = before.copy(appending = false, appendError = AppError(ErrorCode.INVALID_QUERY, "Paging made no progress. Retry.", true)); return
                }
                val drop = (merged.size - MAX_ITEMS).coerceAtLeast(0)
                if (drop > 0) {
                    val retainedAnchor = savedState.get<String>("anchorId")
                    val newIndex = merged.drop(drop).indexOfFirst { it.id.value == retainedAnchor }
                    anchor(if (newIndex >= 0) newIndex else (anchorIndex() - drop).coerceAtLeast(0), anchorOffset())
                    boundaries.firstOrNull { it.first == drop }?.let { saveCursor(it.second) }
                    val remaining = boundaries.filter { it.first > drop }.map { it.first - drop to it.second }
                    boundaries.clear(); boundaries.addAll(remaining)
                }
                result.value.next?.let { boundaries.add(merged.size - drop to it) }
                mutable.value = before.copy(titles = merged.drop(drop), next = result.value.next, appending = false,
                    appendError = null, dropped = before.dropped + drop)
            }
        }
    }
    companion object { const val MAX_ITEMS = 600 }
}
