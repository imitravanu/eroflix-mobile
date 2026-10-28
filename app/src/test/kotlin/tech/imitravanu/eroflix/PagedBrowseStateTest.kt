package tech.imitravanu.eroflix

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tech.imitravanu.eroflix.feature.catalog.BrowseState
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*

@OptIn(ExperimentalCoroutinesApi::class)
class PagedBrowseStateTest {
    @get:Rule val main = MainDispatcherRule()
    private class Catalog(val personal: FixturePersonalRepository) : CatalogRepository {
        override val activeGeneration = MutableStateFlow<GenerationId?>(GenerationId("one"))
        val titles = (0 until 1500).map { TitleSummary(TitleId("title:$it"), "Title ${it.toString().padStart(4, '0')}") }
        val requests = mutableListOf<CatalogQuery>()
        var facetRequests = 0
        var failAppend = false
        var wrongStamp = false
        var latency = 0L
        var durationAvailable = true
        var cancellations = 0
        private fun stamp(q: CatalogQuery) = QueryStamp(q.key(), activeGeneration.value!!, personal.snapshot.value.revision)
        override suspend fun query(query: CatalogQuery): Outcome<Page<TitleSummary>> {
            requests.add(query)
            if (latency > 0) try { delay(latency) } catch (e: CancellationException) { cancellations++; throw e }
            if (failAppend && query.cursor != null) return Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY, "Retry append", true))
            val start = query.cursor?.lastTitleId?.value?.substringAfter(':')?.toInt()?.plus(1) ?: 0
            val stamp = stamp(query).let { if (wrongStamp) it.copy(generation = GenerationId("obsolete")) else it }
            val visible = titles.drop(start).filter { personal.snapshot.value.entries[it.id]?.personal?.hidden != true }
            val items = visible.take(query.limit)
            val next = if (visible.size > items.size) PageCursor(stamp, items.last().title, items.last().id) else null
            return Outcome.Success(Page(items, next, stamp))
        }
        override suspend fun facets(query: CatalogQuery): Outcome<CatalogFacets> { facetRequests++;return Outcome.Success(CatalogFacets(emptyList(),
            listOf(FacetOption(FacetValue.Unknown,"Unknown",1,FacetValue.Unknown in query.countries),
                FacetOption(FacetValue.Known("compatible"),"Compatible",1,FacetValue.Known("compatible") in query.countries),
                FacetOption(FacetValue.Known("incompatible"),"Incompatible",0,FacetValue.Known("incompatible") in query.countries)),
            emptyList(), emptyList(), false, stamp(query))) }
        override suspend fun details(id: TitleId) = Outcome.Failure(AppError(ErrorCode.NOT_FOUND, "Unused"))
        override suspend fun count(query: CatalogQuery) = Outcome.Success(CatalogCount(stamp(query), if (!durationAvailable && query.duration != DurationFilter()) 0 else (1500 - personal.snapshot.value.entries.values.count { it.personal.hidden }).toLong()))
    }
    private fun TestScope.create(catalog: Catalog, handle: SavedStateHandle = SavedStateHandle()): Pair<BrowseViewModel, ViewModelStore> {
        val model = BrowseViewModel(catalog, catalog.personal, handle, main.dispatcher)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        return model to ViewModelStore().also { it.put("browse", model) }
    }
    private fun BrowseViewModel.ready() = state.value as BrowseState.Ready

    @Test fun deepWindowRecreationRestoresSameIdentityOffsetAndNeverRetainsMoreThan600() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository())
        val handle = SavedStateHandle()
        val (model, store) = create(catalog, handle)
        advanceUntilIdle()
        repeat(14) { model.append(); advanceUntilIdle(); assertTrue(model.ready().titles.size <= 600) }
        val title = model.ready().titles[350]
        model.anchor(350, 37, title.id.value)
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        store.clear()
        val (replacement, replacementStore) = create(catalog, restored)
        try {
            advanceUntilIdle()
            assertEquals(title.id, replacement.ready().titles[replacement.anchorIndex()].id)
            assertEquals(37, replacement.anchorOffset())
            assertTrue(replacement.ready().titles.size <= 600)
            assertNotNull(catalog.requests.last { it.cursor != null }.cursor)
        } finally { replacementStore.clear() }
    }
    @Test fun personalRevisionPreservesAnchorButExcludesHiddenBeforeResultsReturn() = runTest(main.dispatcher) {
        val personal = FixturePersonalRepository(); val catalog = Catalog(personal)
        val (model, store) = create(catalog)
        try {
            advanceUntilIdle(); repeat(2) { model.append(); advanceUntilIdle() }
            val target = model.ready().titles[100]
            model.anchor(100, 19, target.id.value)
            personal.setFavorite(target, true); advanceUntilIdle()
            assertEquals(target.id, model.ready().titles[model.anchorIndex()].id)
            assertEquals(19, model.anchorOffset())
            personal.setHidden(catalog.titles.first(), true, 2); advanceUntilIdle()
            assertFalse(model.ready().titles.any { it.id == catalog.titles.first().id })
            assertEquals(target.id, model.ready().titles[model.anchorIndex()].id)
            assertEquals(personal.snapshot.value.revision, model.ready().stamp!!.personalRevision)
        } finally { store.clear() }
    }
    @Test fun restorationAppendFailureStopsAtOneAttemptAndExposesRetry() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository()).apply { failAppend = true }
        val handle = SavedStateHandle(mapOf("anchor" to 300))
        val (model, store) = create(catalog, handle)
        try {
            advanceUntilIdle()
            assertEquals(2, catalog.requests.size)
            assertNotNull(model.ready().appendError)
            catalog.failAppend = false; model.append(); advanceUntilIdle()
            assertEquals(120, model.ready().titles.size)
            assertNull(model.ready().appendError)
        } finally { store.clear() }
    }
    @Test fun debounceCancelsOldQueryAndOnlyPublishesCurrentGeneration() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository()).apply { latency = 1000 }
        val (model, store) = create(catalog)
        try {
            advanceTimeBy(300); runCurrent()
            model.search("first"); advanceTimeBy(300); runCurrent()
            model.search("final"); catalog.activeGeneration.value = GenerationId("two")
            advanceUntilIdle()
            assertTrue(catalog.cancellations >= 1)
            assertEquals(GenerationId("two"), model.ready().stamp!!.generation)
            assertEquals(CatalogQuery(search = "final").key(), model.ready().stamp!!.queryKey)
        } finally { store.clear() }
    }
    @Test fun obsoleteStampsNeverBecomeVisibleAndRetryRecovers() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository()).apply { wrongStamp = true }
        val (model, store) = create(catalog)
        try {
            advanceUntilIdle(); assertTrue(model.state.value is BrowseState.Failed)
            catalog.wrongStamp = false; model.retry(); advanceUntilIdle()
            assertEquals(60, model.ready().titles.size)
        } finally { store.clear() }
    }
    @Test fun providerChangeRetainsCompatibleSelectionsExplainsClearedFiltersAndRestoredZeroIsRemovable() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository()).apply { durationAvailable = false }
        val (model, store) = create(catalog)
        try {
            advanceUntilIdle()
            model.toggle("country",FacetValue.Known("compatible")); model.toggle("country",FacetValue.Known("incompatible"))
            model.duration("short"); advanceUntilIdle()
            // Ordinary/restored zero-count selections are not silently removed.
            assertEquals(2,model.request().countries.size)
            model.toggle("provider",FacetValue.Known("new-provider")); advanceUntilIdle()
            assertEquals(setOf(FacetValue.Known("compatible")),model.request().countries)
            assertEquals(DurationFilter(),model.request().duration)
            assertTrue(model.notice.value!!.contains("1 country"))
            assertTrue(model.notice.value!!.contains("duration"))
            model.toggle("country",FacetValue.Known("incompatible")); advanceUntilIdle()
            assertTrue(FacetValue.Known("incompatible") in model.request().countries)
            model.toggle("country",FacetValue.Known("incompatible")); advanceUntilIdle()
            assertFalse(FacetValue.Known("incompatible") in model.request().countries)
        } finally { store.clear() }
    }
    @Test fun inactiveRouteDoesNoQueryOrFacetsAndResubscribeLoadsLatestStampWithoutOldResults() = runTest(main.dispatcher) {
        val personal=FixturePersonalRepository();val catalog=Catalog(personal)
        val model=BrowseViewModel(catalog,personal,SavedStateHandle(),main.dispatcher)
        val store=ViewModelStore().also { it.put("browse",model) }
        var collector: Job?=null
        try {
            advanceUntilIdle();assertTrue(catalog.requests.isEmpty());assertEquals(0,catalog.facetRequests)
            collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            advanceUntilIdle();repeat(2) { model.append();advanceUntilIdle() }
            val target=model.ready().titles[100]
            model.anchor(100,23,target.id.value)
            collector.cancel();advanceUntilIdle()
            assertEquals(BrowseState.Loading,model.state.value)
            val requests=catalog.requests.size;val facets=catalog.facetRequests
            repeat(3) { personal.recordProgress(PlaybackObservation(target,SourceId("test-source"),"timeline",it*1000L,90000,it.toLong())) }
            personal.setHidden(catalog.titles.first(),true,4)
            advanceUntilIdle();assertEquals(requests,catalog.requests.size);assertEquals(facets,catalog.facetRequests)
            val seen=mutableListOf<BrowseState>()
            collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { seen.add(it) } }
            assertEquals(BrowseState.Loading,model.state.value)
            advanceUntilIdle()
            assertEquals(target.id,model.ready().titles[model.anchorIndex()].id)
            assertEquals(23,model.anchorOffset())
            assertFalse(model.ready().titles.any { it.id==catalog.titles.first().id })
            assertTrue(seen.filterIsInstance<BrowseState.Ready>().all { it.stamp!!.personalRevision==personal.snapshot.value.revision })
            collector.cancel();advanceUntilIdle()
            val before=catalog.requests.size
            catalog.activeGeneration.value=GenerationId("replacement");personal.setSaved(target,true);advanceUntilIdle()
            assertEquals(before,catalog.requests.size)
            collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            advanceUntilIdle()
            assertEquals(GenerationId("replacement"),model.ready().stamp!!.generation)
            assertEquals(personal.snapshot.value.revision,model.ready().stamp!!.personalRevision)
            assertEquals(0,model.anchorIndex())
        } finally { collector?.cancel();store.clear() }
    }
    @Test fun selectedUnknownFacetsSortDensityAndEntityRemainTypedAfterRecreation() = runTest(main.dispatcher) {
        val catalog = Catalog(FixturePersonalRepository()); val handle = SavedStateHandle()
        val entity = EntityRef(EntityKind.COUNTRY, "country-key", "Country")
        val model = BrowseViewModel(catalog, catalog.personal, handle, main.dispatcher, entity)
        val store = ViewModelStore().also { it.put("browse", model) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        model.toggle("provider", FacetValue.Known("provider-key")); model.toggle("country", FacetValue.Unknown)
        model.sort(CatalogSort.YEAR_NEWEST); model.density(220); model.duration("unknown")
        advanceUntilIdle()
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }); store.clear()
        val replacement = BrowseViewModel(catalog, catalog.personal, restored, main.dispatcher, entity)
        val replacementStore = ViewModelStore().also { it.put("browse", replacement) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { replacement.state.collect() }
        try {
            advanceUntilIdle()
            val q = catalog.requests.last()
            assertEquals(setOf(FacetValue.Known("provider-key")), q.providers)
            assertEquals(setOf(FacetValue.Unknown), q.countries)
            assertEquals(entity, q.entity); assertTrue(q.duration.onlyUnknown)
            assertEquals(CatalogSort.YEAR_NEWEST, q.sort); assertEquals(220, replacement.density.value)
        } finally { replacementStore.clear() }
    }
}
