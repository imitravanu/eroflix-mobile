package tech.imitravanu.eroflix

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.core.network.CatalogSourceResolver
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.FixturePersonalRepository

/** Explicit -e allowRealProvider true only. One retained sample, no provider sweep or transfer. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ProductionPlayerRealSourceTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun frozenTubeIndexZeroUsesProductionServiceWithinReadAndTimeBudget() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue("Explicit real-provider opt-in is required",InstrumentationRegistry.getArguments().getString("allowRealProvider")=="true")
        val context=instrumentation.targetContext
        val samples=context.assets.open("source-lab/samples.json").bufferedReader().use { JSONObject(it.readText()) }
        val raw=samples.getJSONObject("samples").getJSONArray("tubepornclassic.com").getJSONObject(0)
        val source=SourceHandle(TitleId(raw.getString("titleId")),SourceId(raw.getString("sourceId")),raw.getString("provider"),raw.getString("url"),StoredSourceKind.valueOf(raw.getString("kind")))
        val title=TitleSummary(source.titleId,"Frozen provider playback validation")
        val catalog=object: CatalogRepository {
            override val activeGeneration=MutableStateFlow<GenerationId?>(GenerationId("p05-real-isolated"))
            override suspend fun query(query: CatalogQuery): Outcome<Page<TitleSummary>> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"No discovery in isolated validation."))
            override suspend fun facets(query: CatalogQuery): Outcome<CatalogFacets> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"No facets in isolated validation."))
            override suspend fun details(id: TitleId): Outcome<TitleDetails> = if(id==title.id) Outcome.Success(TitleDetails(title,null,listOf(source),emptyList(),"Frozen source handle; neutral display label.")) else Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Not the selected source title."))
        }
        val app=context.applicationContext as EroflixApplication
        ui.waitUntil(30_000) { app.playbackOrNull!=null }
        val original=app.playback
        fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
        main { original.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        ui.waitUntil(10_000) { original.surfacePlayer.value==null }
        val personal=FixturePersonalRepository()
        val preferences="p05-real-${System.nanoTime()}"
        var budget: BudgetDataSource.Factory?=null
        val owner=ProductionPlayback(context,catalog,personal,CatalogSourceResolver(),checkpointName=preferences,nativeDataSource={ media ->
            BudgetDataSource.Factory(scopedMediaDataSource(media),5L*1024*1024).also { budget=it }
        })
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var scenario: ActivityScenario<ProductionPlayerHarnessActivity>?=null
        try {
            main {
                app.playback=owner;ProductionHarness.owner=owner;ProductionHarness.catalog=catalog
                // Probe cap is explicit and actual track availability remains authoritative.
                scope.launch { owner.surfacePlayer.filterNotNull().collect { player -> player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setMaxVideoSize(640,360).build() } }
            }
            scenario=ActivityScenario.launch(Intent(context,ProductionPlayerHarnessActivity::class.java))
            val started=SystemClock.elapsedRealtime()
            runBlocking { withContext(Dispatchers.Main) { assertTrue(owner.open(source) is Outcome.Success) } }
            ui.waitUntil(25_000) {
                check(SystemClock.elapsedRealtime()-started<=25_000) { "Bounded real source deadline reached" }
                check(owner.ui.value.error==null) { "Bounded real source failed: ${owner.ui.value.error?.code}" }
                var advanced=false
                main { val p=owner.surfacePlayer.value as? ExoPlayer
                    advanced=p!=null && p.currentPosition>2000 && (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0)>0 && (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0 }
                advanced
            }
            main {
                val p=owner.surfacePlayer.value as ExoPlayer
                assertEquals(MimeTypes.VIDEO_H264,p.videoFormat?.sampleMimeType);assertEquals(MimeTypes.AUDIO_AAC,p.audioFormat?.sampleMimeType)
                assertEquals(source.id,owner.ui.value.source!!.id);assertEquals(source.titleId,owner.ui.value.title!!.id)
                owner.pause()
            }
            assertNotNull(budget);assertTrue(budget!!.consumed<=5L*1024*1024)
            android.util.Log.i("EroflixP05","REAL frozenFamily=tube index=0 native=H264_AAC_advanced mediaReadBytes=${budget!!.consumed} elapsedMs=${SystemClock.elapsedRealtime()-started} probeMaxHeight=360 fullTransfer=not-attempted audibility=not-measured")
        } finally {
            scenario?.close()
            main { scope.cancel();owner.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
            ui.waitUntil(10_000) { owner.surfacePlayer.value==null }
            main { owner.dispose();app.playback=original;ProductionHarness.owner=null;ProductionHarness.catalog=null }
            context.deleteSharedPreferences(preferences)
        }
    }
}
