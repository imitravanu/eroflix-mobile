package tech.imitravanu.eroflix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*

/** No providers, native engine, app database, service or network are opened. */
@RunWith(AndroidJUnit4::class)
class ProductionPlayerRaceTest {
    private val source=SourceHandle(fixtureTitles.first().id,SourceId("race-source"),"fixture","https://example.invalid/race",StoredSourceKind.FILE)
    @Test fun delayedResolveHomeBackAndLockCannotPublishOrStartPlayback()=runBlocking {
        for(action in listOf<(ProductionPlayback)->Unit>({ it.visibility(false,false) },{ it.leaveRoute() },{ it.lock(true) })) {
            val personal=FixturePersonalRepository();val catalog=FixtureCatalogRepository(personal)
            var completed=false
            val resolver=object: SourceResolver {
                override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution {
                    // Non-cooperative completion exercises admission recheck as well as cancellation.
                    withContext(NonCancellable) { delay(150) };completed=true
                    return Resolution.NativeMedia("https://example.invalid/late.mp4",androidx.media3.common.MimeTypes.VIDEO_MP4,
                        listOf(RequestScope(setOf("example.invalid"))),null,"late",null,emptyList(),MediaCapabilities(true,false,false))
                }
            }
            val owner=withContext(Dispatchers.Main) { ProductionPlayback(InstrumentationRegistry.getInstrumentation().targetContext,catalog,personal,resolver) }
            try { withContext(Dispatchers.Main) {
                assertTrue(owner.open(source) is Outcome.Success)
                delay(20);action(owner);delay(250)
                assertTrue(completed)
                assertFalse(owner.ui.value.error?.message=="late-result")
                assertNull(owner.surfacePlayer.value)
                assertFalse(owner.serviceAllowed())
                assertEquals(0,owner.serviceStartRequests)
                owner.stop()
                if(owner.ui.value.locked) assertTrue(owner.open(source) is Outcome.Failure)
            } } finally { withContext(Dispatchers.Main) { owner.dispose() } }
        }
    }
    @Test fun failedResolutionPreservesCheckpointAndLockSurvivesStop()=runBlocking {
        val personal=FixturePersonalRepository();val title=fixtureTitles.first()
        personal.recordProgress(PlaybackObservation(title,source.id,"known",42000,90000,1))
        val owner=withContext(Dispatchers.Main) { ProductionPlayback(InstrumentationRegistry.getInstrumentation().targetContext,FixtureCatalogRepository(personal),personal,FixtureSourceResolver()) }
        try { withContext(Dispatchers.Main) {
            owner.open(source);delay(100)
            assertEquals(42000L,personal.snapshot.value.entries[title.id]!!.personal.positionMs)
            owner.lock(true);owner.stop()
            assertTrue(owner.ui.value.locked)
            assertTrue(owner.open(source) is Outcome.Failure)
            assertNull(owner.surfacePlayer.value)
        } } finally { withContext(Dispatchers.Main) { owner.dispose() } }
    }
    @Test fun missingSourceRestorationExplainsFailureWithoutSubstitutionOrAutoplay()=runBlocking {
        val personal=FixturePersonalRepository();val catalog=FixtureCatalogRepository(personal)
        var calls=0
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution {
                calls++;return Resolution.Unavailable(AppError(ErrorCode.UNSUPPORTED,"unused"))
            }
        }
        val owner=withContext(Dispatchers.Main) { ProductionPlayback(InstrumentationRegistry.getInstrumentation().targetContext,catalog,personal,resolver) }
        try { withContext(Dispatchers.Main) {
            owner.restoreRoute(fixtureTitles.first().id,SourceId("removed-source"));delay(20)
            assertEquals(PlaybackPhase.FAILED,owner.ui.value.phase)
            assertEquals(ErrorCode.NOT_FOUND,owner.ui.value.error?.code)
            assertNull(owner.ui.value.source);assertNull(owner.surfacePlayer.value)
            assertEquals(0,calls);assertEquals(owner.ui.value.error,owner.state.value.error)
            assertEquals(0,owner.serviceStartRequests)
        } } finally { withContext(Dispatchers.Main) { owner.dispose() } }
    }
}
