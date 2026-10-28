package tech.imitravanu.eroflix.model
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test fun titleIdentityIsPreservedExactly() {
        val raw = "provider:Title%20ID "
        assertEquals(raw, TitleId(raw).value)
    }
    @Test fun sourceIdentityIncludesOriginalUrlAndHasUnambiguousFields() {
        val title = TitleId("fixture:one")
        val url = "https://example.invalid/v?a=1&b=2"
        assertEquals(sourceId(title, "source", StoredSourceKind.FILE, url), sourceId(title, "source", StoredSourceKind.FILE, url))
        assertNotEquals(sourceId(title, "source", StoredSourceKind.FILE, url), sourceId(title, "source", StoredSourceKind.FILE, "https://example.invalid/v?b=2&a=1"))
        assertNotEquals(stableDigest("ab", "c"), stableDigest("a", "bc"))
    }
    @Test fun queryKeyIsIndependentOfSetInsertionOrderAndCursor() {
        val one = CatalogQuery(countries = linkedSetOf(FacetValue.Known("a"), FacetValue.Unknown))
        val two = one.copy(countries = linkedSetOf(FacetValue.Unknown, FacetValue.Known("a")))
        assertEquals(one.key(), two.key())
        assertEquals(one.key(), one.copy(cursor = PageCursor(QueryStamp(one.key(), GenerationId("g"), 0), "a", TitleId("t"))).key())
        assertNotEquals(one.key(), one.copy(search = "changed").key())
        assertNotEquals(CatalogQuery(countries = setOf(FacetValue.Unknown)).key(), CatalogQuery(countries = setOf(FacetValue.Known("Unknown"))).key())
    }
    @Test fun unknownOnlyDurationHasDistinctQueryIdentity() {
        assertNotEquals(CatalogQuery().key(),CatalogQuery(duration=DurationFilter(onlyUnknown=true)).key())
        assertNotEquals(CatalogQuery(duration=DurationFilter(includeUnknown=false)).key(),CatalogQuery(duration=DurationFilter(onlyUnknown=true)).key())
    }
    @Test(expected = IllegalArgumentException::class) fun pageLimitCannotBeUnbounded() { CatalogQuery(limit = 121) }
    @Test(expected = IllegalArgumentException::class) fun invalidDurationRangeIsRejected() { DurationFilter(minMs = 100, maxMs = 10) }
    @Test(expected = IllegalArgumentException::class) fun downloadSourceMustBelongToRequestedTitle() {
        val source = SourceHandle(TitleId("other"), SourceId("s"), "fixture", "https://example.invalid", StoredSourceKind.FILE)
        DownloadIntent(TitleSummary(TitleId("requested"), "Fixture"), source, TrackSelection(emptySet()))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPlaybackSpeedIsRejected() {
        PlaybackObservation(TitleSummary(TitleId("t"),"Fixture"),SourceId("s"),"timeline",0,null,0,speed=Float.NaN)
    }
    @Test(expected = IllegalArgumentException::class) fun negativePlaybackPositionIsRejected() {
        PlaybackObservation(TitleSummary(TitleId("t"), "Fixture"), SourceId("s"), "timeline", -1, null, 0)
    }
}
