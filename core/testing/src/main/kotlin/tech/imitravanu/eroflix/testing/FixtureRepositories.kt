package tech.imitravanu.eroflix.testing

import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.*

/** Neutral synthetic data; this module is a debug/test-only dependency. */
val fixtureTitles = listOf(
    TitleSummary(TitleId("fixture:aurora"), "Aurora Field Notes", 1984),
    TitleSummary(TitleId("fixture:river"), "River Journey", 1992),
    TitleSummary(TitleId("fixture:observatory"), "Night Observatory"),
)
data class FixtureSnapshot(val revision: Long = 0, val entries: Map<TitleId, LibraryItem> = emptyMap())
class FixturePersonalRepository : PersonalRepository {
    private val mutable = MutableStateFlow(FixtureSnapshot())
    val snapshot: StateFlow<FixtureSnapshot> = mutable.asStateFlow()
    override val revision: Flow<Long> = snapshot.map { it.revision }.distinctUntilChanged()
    override fun observeState(titleId: TitleId) = snapshot.map { it.entries[titleId]?.personal ?: PersonalState() }.distinctUntilChanged()
    override fun observeShelf(shelf: LibraryShelf) = snapshot.map { state ->
        state.entries.values.filter { item ->
            val p = item.personal
            if (shelf == LibraryShelf.HIDDEN) p.hidden else !p.hidden && when (shelf) {
                LibraryShelf.FAVORITES -> p.favorite
                LibraryShelf.SAVED -> p.saved
                LibraryShelf.HISTORY -> p.lastWatchedEpochMs != null
                LibraryShelf.CONTINUE_WATCHING -> p.positionMs > 0 && (p.durationMs?.let { p.positionMs < it } ?: true)
                LibraryShelf.HIDDEN -> false
            }
        }.sortedBy { it.title.id.value }
    }
    private fun change(title: TitleSummary, transform: (PersonalState) -> PersonalState): Outcome<Unit> {
        mutable.update { old ->
            val state = old.entries[title.id]?.personal ?: PersonalState()
            FixtureSnapshot(old.revision + 1, old.entries + (title.id to LibraryItem(title, transform(state))))
        }
        return Outcome.Success(Unit)
    }
    override suspend fun setFavorite(title: TitleSummary, enabled: Boolean) = change(title) { it.copy(favorite = enabled) }
    override suspend fun setSaved(title: TitleSummary, enabled: Boolean) = change(title) { it.copy(saved = enabled) }
    override suspend fun setHidden(title: TitleSummary, enabled: Boolean, atEpochMs: Long) = change(title) { it.copy(hidden = enabled, hiddenAtEpochMs = if (enabled) atEpochMs else null) }
    override suspend fun unhideAll(): Outcome<Unit> {
        mutable.update { old -> FixtureSnapshot(old.revision + 1, old.entries.mapValues { (_, item) -> item.copy(personal = item.personal.copy(hidden = false, hiddenAtEpochMs = null)) }) }
        return Outcome.Success(Unit)
    }
    override suspend fun recordProgress(observation: PlaybackObservation) = change(observation.title) {
        it.copy(positionMs = observation.positionMs, durationMs = observation.durationMs, lastWatchedEpochMs = observation.atEpochMs)
    }
}

class FixtureCatalogRepository(private val personal: FixturePersonalRepository) : CatalogRepository {
    private val generation = GenerationId("fixture-v1")
    override suspend fun metadata() = Outcome.Success(CatalogMetadata(generation, fixtureTitles.size.toLong(), 0))
    override suspend fun count(query: CatalogQuery): Outcome<CatalogCount> {
        val page = query(query.copy(cursor = null, limit = 120))
        return when (page) { is Outcome.Failure -> page; is Outcome.Success -> Outcome.Success(CatalogCount(QueryStamp(query.key(), generation, personal.snapshot.value.revision), page.value.items.size.toLong())) }
    }
    override val activeGeneration: StateFlow<GenerationId?> = MutableStateFlow(generation)
    private fun facet(title: TitleSummary, group: String): FacetValue = when (group) {
        "provider" -> FacetValue.Known("fixture")
        "country" -> if (title.id.value == "fixture:observatory") FacetValue.Unknown else FacetValue.Known("Fixture country")
        "decade" -> title.year?.let { FacetValue.Known((it / 10 * 10).toString()) } ?: FacetValue.Unknown
        "studio" -> FacetValue.Known("fixture-archive")
        else -> FacetValue.Unknown
    }
    private fun matches(title: TitleSummary, q: CatalogQuery, excludedFacet: String? = null): Boolean {
        fun selected(group: String, values: Set<FacetValue>) = group == excludedFacet || values.isEmpty() || facet(title, group) in values
        return title.title.contains(q.search.trim(), ignoreCase = true) &&
            selected("provider", q.providers) && selected("country", q.countries) &&
            selected("decade", q.decades) && selected("studio", q.studios) &&
            (q.entity?.let { it.key == "fixture-archive" } ?: true) &&
            (!q.duration.onlyUnknown || title.durationMs == null) &&
            (title.durationMs?.let { duration -> (q.duration.minMs?.let { duration >= it } ?: true) && (q.duration.maxMs?.let { duration <= it } ?: true) } ?: q.duration.includeUnknown)
    }
    override suspend fun query(query: CatalogQuery): Outcome<Page<TitleSummary>> {
        val snapshot = personal.snapshot.value
        val stamp = QueryStamp(query.key(), generation, snapshot.revision)
        if (query.cursor?.let { it.stamp != stamp } == true) return Outcome.Failure(AppError(ErrorCode.INVALID_QUERY, "Results changed; start again at the first page."))
        val visible = fixtureTitles.filter { snapshot.entries[it.id]?.personal?.hidden != true && matches(it, query) }
        val ordered = when (query.sort) {
            CatalogSort.TITLE -> visible.sortedWith(compareBy<TitleSummary> { it.title }.thenBy { it.id.value })
            CatalogSort.YEAR_NEWEST -> visible.sortedWith(compareByDescending<TitleSummary> { it.year ?: Int.MIN_VALUE }.thenBy { it.id.value })
            CatalogSort.YEAR_OLDEST -> visible.sortedWith(compareBy<TitleSummary> { it.year ?: Int.MAX_VALUE }.thenBy { it.id.value })
        }
        val start = query.cursor?.let { cursor ->
            val index = ordered.indexOfFirst { it.id == cursor.lastTitleId }
            if (index < 0) return Outcome.Failure(AppError(ErrorCode.INVALID_QUERY, "Page anchor is unavailable."))
            index + 1
        } ?: 0
        val items = ordered.drop(start).take(query.limit)
        val next = if (start + items.size < ordered.size && items.isNotEmpty()) PageCursor(stamp, items.last().title, items.last().id) else null
        return Outcome.Success(Page(items, next, stamp))
    }
    override suspend fun facets(query: CatalogQuery): Outcome<CatalogFacets> {
        val snapshot = personal.snapshot.value
        fun options(group: String, selected: Set<FacetValue>): List<FacetOption> {
            val counts = fixtureTitles.filter { snapshot.entries[it.id]?.personal?.hidden != true && matches(it, query, group) }.groupingBy { facet(it, group) }.eachCount()
            return (counts.keys + selected).map { value -> FacetOption(value, when (value) { is FacetValue.Known -> value.key; FacetValue.Unknown -> "Unknown" }, counts[value]?.toLong() ?: 0, value in selected) }.sortedBy { it.label }
        }
        return Outcome.Success(CatalogFacets(options("provider", query.providers), options("country", query.countries), options("decade", query.decades), options("studio", query.studios), false, QueryStamp(query.key(), generation, snapshot.revision)))
    }
    override suspend fun details(id: TitleId): Outcome<TitleDetails> {
        val title = fixtureTitles.find { it.id == id } ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, "Title is unavailable."))
        return Outcome.Success(TitleDetails(title, "A neutral demonstration record used to build and test navigation.", emptyList(), listOf(EntityRef(EntityKind.STUDIO, "fixture-archive", "Fixture Archive")), "Synthetic fixture; not production catalog data."))
    }
}
