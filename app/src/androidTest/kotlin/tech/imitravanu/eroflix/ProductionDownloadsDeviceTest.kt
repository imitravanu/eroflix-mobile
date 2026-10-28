package tech.imitravanu.eroflix

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Neutral loopback/cache test. Network-loss simulation affects only its own fixture server. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ProductionDownloadsDeviceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(predicate: ()->Boolean)=ui.waitUntil(30_000,predicate)
    private fun copyAssets(path: String,out: File) {
        val children=context.assets.list(path).orEmpty()
        if(children.isEmpty()) { out.parentFile!!.mkdirs();context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
        else { out.mkdirs();children.forEach { copyAssets("$path/$it",File(out,it)) } }
    }
    private class Store: DownloadIntentStore {
        val entries=ConcurrentHashMap<String,PersistedDownload>()
        override suspend fun downloads()=Outcome.Success(entries.values.sortedBy { it.order })
        override suspend fun putDownload(record: PersistedDownload): Outcome<Unit> { entries[record.id.value]=record;return Outcome.Success(Unit) }
        override suspend fun deleteDownload(id: JobId): Outcome<Unit> { entries.remove(id.value);return Outcome.Success(Unit) }
    }
    private fun <T> ok(outcome: Outcome<T>): T=when(outcome) { is Outcome.Success -> outcome.value;is Outcome.Failure -> throw AssertionError(outcome.error.toString()) }
    @Test fun selectedQueueDedupPauseReopenCompleteAndNoUpstreamPlayback(): Unit=runBlocking {
        val app=context.applicationContext as EroflixApplication
        waitFor { app.downloadOwner.value!=null }
        val originalDownload=app.downloadOwner.value!!;val originalPlayer=app.playback
        Assume.assumeTrue("Do not interrupt existing user transfers",originalDownload.jobs.value.none { it.status in listOf(DownloadStatus.DOWNLOADING,DownloadStatus.QUEUED,DownloadStatus.RESOLVING) })
        main { context.stopService(Intent(context,ProductionDownloadService::class.java));originalPlayer.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        waitFor { originalPlayer.surfacePlayer.value==null && !originalDownload.isForegroundActive }
        val root=File(context.cacheDir,"p06-${System.nanoTime()}")
        val media=File(root,"media");copyAssets("source-lab",media)
        val server=LabFixtureServer(media).apply { slow=true }
        val personal=FixturePersonalRepository();val catalog=FixtureCatalogRepository(personal);val store=Store()
        val prefs="p06-${System.nanoTime()}"
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose)=Resolution.NativeMedia(source.originalUrl,
                if(source.kind==StoredSourceKind.HLS) "application/x-mpegURL" else "video/mp4",
                listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),null,source.id.value,null,emptyList(),MediaCapabilities(true,true,false),
                if(source.kind==StoredSourceKind.FILE) ResourceValidators("\"neutral-neutral.mp4-${File(media,"neutral.mp4").length()}\"",null,File(media,"neutral.mp4").length(),true) else null)
        }
        val player=ProductionPlayback(context,catalog,personal,resolver,true,"$prefs-player")
        var downloads=ProductionDownloads.create(context,store,resolver,player,File(root,"durable"),prefs,true,{ true })
        var scenario: ActivityScenario<ProductionPlayerHarnessActivity>?=null
        try {
            main { app.playback=player;app.downloadMutable.value=downloads;ProductionHarness.owner=player;ProductionHarness.catalog=catalog }
            scenario=ActivityScenario.launch(Intent(context,ProductionPlayerHarnessActivity::class.java))
            val source=SourceHandle(fixtureTitles.first().id,SourceId("p06-file"),"Neutral fixture","${server.origin}/neutral.mp4",StoredSourceKind.FILE)
            val intent=DownloadIntent(fixtureTitles.first(),source,TrackSelection(setOf("ambiguous-advisory-id")))
            val preparation=withContext(Dispatchers.Main) { downloads.enqueue(intent) }
            assertNotNull("Expected selection offer; preparation=$preparation",downloads.offer.value)
            val offer=downloads.offer.value!!
            val id=withContext(Dispatchers.Main) { ok(downloads.confirm(offer.token,emptyList())) }
            waitFor { downloads.jobs.value.any { it.id==id && it.downloadedBytes>0 } }
            withContext(Dispatchers.Main) { ok(downloads.pause(id)) }
            assertEquals(DownloadDesired.PAUSE_USER,store.entries[id.value]!!.desired)
            main { context.stopService(Intent(context,ProductionDownloadService::class.java)) }
            waitFor { !downloads.isForegroundActive }
            downloads.close()
            downloads=ProductionDownloads.create(context,store,resolver,player,File(root,"durable"),prefs,true,{ true })
            main { app.downloadMutable.value=downloads }
            waitFor { downloads.jobs.value.any { it.id==id && it.status==DownloadStatus.PAUSED_USER } }
            withContext(Dispatchers.Main) { ok(downloads.resume(id)) }
            waitFor { downloads.jobs.value.any { it.id==id && it.status==DownloadStatus.COMPLETED } }
            withContext(Dispatchers.Main) { downloads.enqueue(intent) }
            val again=downloads.offer.value!!
            assertEquals(id,withContext(Dispatchers.Main) { ok(downloads.confirm(again.token,emptyList())) })
            assertEquals(1,store.entries.size)
            server.interruptNetwork()
            val before=server.observed.size
            withContext(Dispatchers.Main) { ok(downloads.openOffline(id)) }
            waitFor { player.ui.value.positionMs>1000 && player.ui.value.phase==PlaybackPhase.PLAYING }
            main {
                val engine=player.surfacePlayer.value as ExoPlayer
                assertTrue((engine.videoDecoderCounters?.renderedOutputBufferCount ?: 0)>0)
                assertTrue((engine.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0)
                player.stop()
            }
            assertEquals(before,server.observed.size)
            withContext(Dispatchers.Main) { ok(downloads.remove(id)) }
            waitFor { downloads.jobs.value.isEmpty() && store.entries.isEmpty() }
            android.util.Log.i("EroflixP06","NEUTRAL persistentIndex=paused-reopen dedup=same-id complete=true cacheOnly=video-audio noUpstream=true remove=true")
        } finally {
            scenario?.close()
            main { player.stop();context.stopService(Intent(context,PlaybackService::class.java));context.stopService(Intent(context,ProductionDownloadService::class.java)) }
            waitFor { player.surfacePlayer.value==null && !downloads.isForegroundActive }
            downloads.close()
            main { player.dispose();app.playback=originalPlayer;app.downloadMutable.value=originalDownload;ProductionHarness.owner=null;ProductionHarness.catalog=null }
            server.close();root.deleteRecursively();context.deleteSharedPreferences(prefs);context.deleteSharedPreferences("$prefs-player")
        }
    }
}
