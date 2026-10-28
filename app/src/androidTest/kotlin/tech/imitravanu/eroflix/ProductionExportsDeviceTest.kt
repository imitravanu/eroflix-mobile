package tech.imitravanu.eroflix

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Controlled complete-download → cache-only Transformer → independently readable MP4. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ProductionExportsDeviceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(predicate: ()->Boolean)=ui.waitUntil(40_000,predicate)
    private fun copyAssets(path: String,out: File) {
        val children=context.assets.list(path).orEmpty()
        if(children.isEmpty()) { out.parentFile!!.mkdirs();context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
        else { out.mkdirs();children.forEach { copyAssets("$path/$it",File(out,it)) } }
    }
    private class Store: DownloadIntentStore,ExportIntentStore {
        val downloads=ConcurrentHashMap<String,PersistedDownload>();val exports=ConcurrentHashMap<String,PersistedExport>()
        override suspend fun downloads()=Outcome.Success(downloads.values.toList())
        override suspend fun putDownload(record: PersistedDownload): Outcome<Unit> { downloads[record.id.value]=record;return Outcome.Success(Unit) }
        override suspend fun deleteDownload(id: JobId): Outcome<Unit> { downloads.remove(id.value);return Outcome.Success(Unit) }
        override suspend fun exportIntents()=Outcome.Success(exports.values.toList())
        override suspend fun putExport(record: PersistedExport): Outcome<Unit> { exports[record.job.id.value]=record;return Outcome.Success(Unit) }
    }
    private fun <T> ok(outcome: Outcome<T>): T=when(outcome) { is Outcome.Success -> outcome.value;is Outcome.Failure -> throw AssertionError(outcome.error.toString()) }
    @Test fun completedProgressiveTsAndFmp4RemuxWithoutUpstreamAndRetainDownloads(): Unit=runBlocking {
        val app=context.applicationContext as EroflixApplication
        waitFor { app.downloadOwner.value!=null && app.exportOwner.value!=null }
        val originalDownload=app.downloadOwner.value!!;val originalPlayer=app.playback;val originalExport=app.exportOwner.value!!
        Assume.assumeTrue("Do not interrupt user transfers",originalDownload.jobs.value.none { it.status in listOf(DownloadStatus.DOWNLOADING,DownloadStatus.QUEUED,DownloadStatus.RESOLVING) })
        Assume.assumeTrue("Do not interrupt user exports",originalExport.exports.value.none { it.status in listOf(ExportStatus.EXPORTING,ExportStatus.QUEUED) })
        main { context.stopService(Intent(context,ProductionDownloadService::class.java));originalPlayer.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        waitFor { originalPlayer.surfacePlayer.value==null && !originalDownload.isForegroundActive }
        val root=File(context.cacheDir,"p07-${System.nanoTime()}");val media=File(root,"media");copyAssets("source-lab",media)
        val server=LabFixtureServer(media);val personal=FixturePersonalRepository();val catalog=FixtureCatalogRepository(personal);val store=Store()
        val prefs="p07-${System.nanoTime()}"
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose)=Resolution.NativeMedia(source.originalUrl,
                if(source.kind==StoredSourceKind.HLS) "application/x-mpegURL" else "video/mp4",
                listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),null,source.id.value,null,emptyList(),MediaCapabilities(true,true,true))
        }
        val player=ProductionPlayback(context,catalog,personal,resolver,true,"$prefs-player")
        val downloads=ProductionDownloads.create(context,store,resolver,player,File(root,"durable"),prefs,true,{ true })
        val exports=ProductionExports.create(context,store,downloads,File(root,"staging"))
        var scenario: ActivityScenario<ProductionPlayerHarnessActivity>?=null
        val destinations=File(context.cacheDir,"exports/$prefs").apply { mkdirs() }
        try {
            main { app.playback=player;app.downloadMutable.value=downloads;app.exportMutable.value=exports;ProductionHarness.owner=player;ProductionHarness.catalog=catalog }
            scenario=ActivityScenario.launch(Intent(context,ProductionPlayerHarnessActivity::class.java))
            val jobs=mutableListOf<JobId>()
            for((index,path) in listOf("neutral.mp4","ts/master.m3u8","fmp4/master.m3u8").withIndex()) {
                val kind=if(index==0) StoredSourceKind.FILE else StoredSourceKind.HLS
                val source=SourceHandle(fixtureTitles.first().id,SourceId("p07-$index"),"Neutral fixture","${server.origin}/$path",kind)
                withContext(Dispatchers.Main) { downloads.enqueue(DownloadIntent(fixtureTitles.first(),source,TrackSelection(emptySet()))) }
                val offer=downloads.offer.value ?: throw AssertionError("Expected prepared source selection")
                val selected=offer.options.filter { it.type in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO) }.groupBy { it.period to it.type }.values.map { choices -> choices.minBy { it.height ?: 0 } }
                val id=withContext(Dispatchers.Main) { ok(downloads.confirm(offer.token,selected)) };jobs+=id
                waitFor { downloads.jobs.value.any { it.id==id && it.status==DownloadStatus.COMPLETED } }
            }
            jobs.forEachIndexed { index, id ->
                val keys = downloads.cache.keys.filter { it.startsWith("${id.value}|") }
                val root = "${id.value}|${server.origin}/${listOf("neutral.mp4", "ts/master.m3u8", "fmp4/master.m3u8")[index]}"
                android.util.Log.i("EroflixP07", "CACHE_INSPECT format=$index keys=${keys.size} rootPresent=${root in keys} manifestCount=${keys.count { it.substringAfter('|').substringBefore('?').endsWith(".m3u8") }}")
            }
            // Export must not resolve or fetch anything; all three source formats are cached.
            server.interruptNetwork();val before=server.observed.size
            for((index,id) in jobs.withIndex()) {
                val destination=File(destinations,"format-$index.mp4")
                val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",destination)
                val exportId=withContext(Dispatchers.Main) {
                    val exportId=ok(exports.export(id,uri.toString()))
                    assertTrue("Queued export must retain its input lease",downloads.remove(id) is Outcome.Failure)
                    exportId
                }
                waitFor { exports.exports.value.any { it.id==exportId && it.status in listOf(ExportStatus.EXPORTED,ExportStatus.FAILED,ExportStatus.CANCELED) } }
                val result=exports.exports.value.first { it.id==exportId }
                assertEquals("format=$index error=${result.error}",ExportStatus.EXPORTED,result.status)
                assertTrue(destination.length()>0)
                assertEquals(DownloadStatus.COMPLETED,downloads.jobs.value.first { it.id==id }.status)
                assertEquals(before,server.observed.size)
                val check=android.media.MediaExtractor()
                try {
                    check.setDataSource(destination.absolutePath)
                    assertEquals(2,check.trackCount)
                    for(track in 0 until check.trackCount) {
                        val format=check.getTrackFormat(track);assertTrue(format.getLong(android.media.MediaFormat.KEY_DURATION)>1_000_000)
                        check.selectTrack(track);check.seekTo(1_000_000,android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        assertTrue(check.readSampleData(java.nio.ByteBuffer.allocate(2*1024*1024),0)>0);check.unselectTrack(track)
                    }
                } finally { check.release() }
                // This factory reads the exported standalone file, not DownloadManager/cache.
                val retained=store.downloads[id.value]!!
                withContext(Dispatchers.Main) { ok(player.openCached(retained.intent.title,retained.intent.source,
                    androidx.media3.common.MediaItem.fromUri(destination.toURI().toString()),androidx.media3.datasource.DefaultDataSource.Factory(context),"export-$index")) }
                waitFor { player.ui.value.positionMs>1000 && player.ui.value.phase==PlaybackPhase.PLAYING }
                main {
                    val engine=player.surfacePlayer.value as androidx.media3.exoplayer.ExoPlayer
                    assertTrue((engine.videoDecoderCounters?.renderedOutputBufferCount ?: 0)>0)
                    assertTrue((engine.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0)
                    player.seekTo((engine.duration/2).coerceAtLeast(1000))
                }
                waitFor { player.ui.value.positionMs>1500 }
                main { player.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
                waitFor { player.surfacePlayer.value==null }
                assertEquals(before,server.observed.size)
                android.util.Log.i("EroflixP07","CONTROLLED format=$index independentTracks=2 seekRead=true standaloneVideoAudioDecode=true upstreamDelta=0 downloadRetained=true")
            }
        } finally {
            scenario?.close()
            main { context.stopService(Intent(context,ProductionExportService::class.java));context.stopService(Intent(context,ProductionDownloadService::class.java));context.stopService(Intent(context,PlaybackService::class.java));player.stop() }
            waitFor { !downloads.isForegroundActive && !exports.isForegroundActive && player.surfacePlayer.value==null }
            exports.close()
            downloads.close()
            main { player.dispose();app.playback=originalPlayer;app.downloadMutable.value=originalDownload;app.exportMutable.value=originalExport;ProductionHarness.owner=null;ProductionHarness.catalog=null }
            server.close();root.deleteRecursively();destinations.deleteRecursively();context.deleteSharedPreferences(prefs);context.deleteSharedPreferences("$prefs-player")
        }
    }
}
