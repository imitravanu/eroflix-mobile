package tech.imitravanu.eroflix.testing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tech.imitravanu.eroflix.model.*

class FixtureRepositoryTest {
    @Test fun hiddenRecordsAreExcludedBeforePagingAndFacetCounts() = runTest {
        val personal = FixturePersonalRepository(); val catalog = FixtureCatalogRepository(personal)
        personal.setHidden(fixtureTitles.first(), true, 1)
        val page = (catalog.query(CatalogQuery(limit = 1)) as Outcome.Success).value
        assertNotEquals(fixtureTitles.first().id, page.items.single().id)
        val facets = (catalog.facets(CatalogQuery()) as Outcome.Success).value
        assertEquals(2L, facets.providers.sumOf { it.titleCount })
    }
    @Test fun staleCursorIsRejectedAfterPersonalRevisionChanges() = runTest {
        val personal = FixturePersonalRepository(); val catalog = FixtureCatalogRepository(personal)
        val page = (catalog.query(CatalogQuery(limit = 1)) as Outcome.Success).value
        personal.setFavorite(fixtureTitles.first(), true)
        val result = catalog.query(CatalogQuery(limit = 1, cursor = page.next))
        assertEquals(ErrorCode.INVALID_QUERY, (result as Outcome.Failure).error.code)
    }
    @Test fun favoriteAndSavedRemainDistinctAndHideUnhidePreservesBoth() = runTest {
        val personal = FixturePersonalRepository(); val title = fixtureTitles.first()
        personal.setSaved(title, true)
        assertFalse(personal.observeState(title.id).first().favorite)
        personal.setFavorite(title, true); personal.setHidden(title, true, 1)
        assertTrue(personal.observeShelf(LibraryShelf.FAVORITES).first().isEmpty())
        assertEquals(title.id, personal.observeShelf(LibraryShelf.HIDDEN).first().single().title.id)
        personal.unhideAll()
        assertEquals(title.id, personal.observeShelf(LibraryShelf.FAVORITES).first().single().title.id)
        assertTrue(personal.observeState(title.id).first().saved)
    }
    @Test fun selectedZeroCountFacetCanStillBeRemoved() = runTest {
        val catalog = FixtureCatalogRepository(FixturePersonalRepository())
        val selected = FacetValue.Known("absent-country")
        val facets = (catalog.facets(CatalogQuery(search = "no-match", countries = setOf(selected))) as Outcome.Success).value
        assertTrue(facets.countries.any { it.value == selected && it.selected && it.titleCount == 0L })
    }
    @Test fun fixtureMediaCannotClaimARealDownload() = runTest {
        val title = fixtureTitles.first()
        val source = SourceHandle(title.id, SourceId("fixture-source"), "fixture", "https://example.invalid/media.mp4", StoredSourceKind.FILE)
        assertTrue(FixtureSourceResolver().resolve(source, ResolvePurpose.DOWNLOAD) is Resolution.Unavailable)
        assertTrue(FixtureDownloadController().enqueue(DownloadIntent(title, source, TrackSelection(emptySet()))) is Outcome.Failure)
    }
}
