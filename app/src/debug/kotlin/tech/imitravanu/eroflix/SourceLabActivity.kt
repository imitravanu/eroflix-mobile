package tech.imitravanu.eroflix

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.*
import android.content.Context
import android.content.ContextWrapper
import android.webkit.MimeTypeMap
import androidx.media3.exoplayer.scheduler.Requirements
import android.content.Intent
import android.webkit.WebResourceResponse
import android.util.Log
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.offline.*
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.*
import kotlinx.coroutines.*
import tech.imitravanu.eroflix.core.network.*
import tech.imitravanu.eroflix.core.media.scopedMediaDataSource
import tech.imitravanu.eroflix.model.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

fun sourceLabIntent(context: Context): Intent? = Intent(context,SourceLabActivity::class.java)

/** Debug-only, non-exported and never a second production media engine. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SourceLabActivity: Activity() {
 private val scope=MainScope()
 private lateinit var status: TextView
 private lateinit var frame: FrameLayout
 private lateinit var surface: SurfaceView
 private var player: ExoPlayer?=null
 private var manager: DownloadManager?=null
 private var cache: SimpleCache?=null
 private var database: StandaloneDatabaseProvider?=null
 private var server: LabFixtureServer?=null
 private var web: ContainedWebPlayer?=null
 private var job: Job?=null
 private lateinit var sampleChoices: LinearLayout
 val containedWeb: ContainedWebPlayer? get()=web
 private val executor=Executors.newFixedThreadPool(2)
 val lastResult=CompletableDeferred<JSONObject>()
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  (application as EroflixApplication).playbackOrNull?.stop()
  stopService(Intent(this,tech.imitravanu.eroflix.core.media.PlaybackService::class.java))
  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,24,16,16) }
  status=TextView(this).apply { text="Source feasibility lab · neutral fixtures only\nNo phone evidence exists until this suite is run." };layout.addView(status)
  layout.addView(Button(this).apply { text="Run controlled media suite";setOnClickListener { runSuite() } })
  layout.addView(Button(this).apply { text="Open containment fixture";setOnClickListener { openWebFixture() } })
  layout.addView(Button(this).apply { text="Probe fixed real-source sample";setOnClickListener { probeSamples() } })
  sampleChoices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  layout.addView(ScrollView(this).apply { addView(sampleChoices) },LinearLayout.LayoutParams(-1,160))
  layout.addView(Button(this).apply { text="Return to app";setOnClickListener { finish() } })
  frame=FrameLayout(this);layout.addView(frame,LinearLayout.LayoutParams(-1,0,1f));surface=SurfaceView(this);frame.addView(surface)
  setContentView(layout)
 }
 private suspend fun fixtures(): File = withContext(Dispatchers.IO) {
  val root=File(cacheDir,"source-lab-input");root.mkdirs()
  fun copy(path: String,dest: File) {
   val names=assets.list(path).orEmpty()
   if(names.isEmpty()) assets.open(path).use { input -> dest.parentFile?.mkdirs();dest.outputStream().use { input.copyTo(it) } }
   else { dest.mkdirs();names.forEach { copy("$path/$it",File(dest,it)) } }
  }
  copy("source-lab",root);root
 }
 private fun nativePlayer(): ExoPlayer {
  check((application as EroflixApplication).playbackOrNull?.surfacePlayer?.value==null) { "Production player is still detaching. Retry the lab action." }
  return player ?: ExoPlayer.Builder(this).setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000,3000,500,500).build()).build().also {
  it.setAudioAttributes(AudioAttributes.DEFAULT,true);it.setVideoSurfaceView(surface);player=it
  }
 }
 private suspend fun advancing(item: MediaItem,factory: DataSource.Factory): JSONObject {
  val p=nativePlayer();p.stop();p.clearMediaItems();p.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item));p.prepare();p.play()
  val advanced=withTimeoutOrNull(20_000) { while(p.currentPosition<2000 || (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0)<=0 || (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0)<=0) {
   p.playerError?.let { throw IllegalStateException("Native decoder/playback failed: ${it.errorCodeName}") };delay(100)
  };true }
  check(advanced==true) { "Video and audio did not advance before the playback deadline" }
  return JSONObject().put("positionMs",p.currentPosition).put("videoMime",p.videoFormat?.sampleMimeType).put("audioMime",p.audioFormat?.sampleMimeType)
   .put("videoFrames",p.videoDecoderCounters?.renderedOutputBufferCount).put("audioBuffers",p.audioDecoderCounters?.renderedOutputBufferCount).also { p.pause() }
 }
 private suspend fun request(item: MediaItem,factory: DataSource.Factory): DownloadRequest = suspendCancellableCoroutine { cont ->
  val helper=DownloadHelper.Factory().setDataSourceFactory(factory)
   .setRenderersFactory(DefaultRenderersFactory(this)).create(item)
  cont.invokeOnCancellation { runOnUiThread { helper.release() } }
  helper.prepare(object: DownloadHelper.Callback {
   override fun onPrepared(helper: DownloadHelper,manifestLoaded: Boolean) {
    try {
     for(period in 0 until helper.periodCount) {
      helper.clearTrackSelections(period);val params=TrackSelectionParameters.Builder(this@SourceLabActivity).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)
      val groups=helper.getTrackGroups(period)
      for(type in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO)) {
       val group=(0 until groups.length).map { groups[it] }.firstOrNull { it.type==type }
       if(group!=null) params.setOverrideForType(TrackSelectionOverride(group,listOf(0)))
      }
      helper.addTrackSelection(period,params.build())
     }
     val request=helper.getDownloadRequest("source-lab-selected",byteArrayOf())
     require(request.streamKeys.count { it.groupIndex==0 }==1)
     helper.release();if(cont.isActive) cont.resumeWith(Result.success(request))
    } catch(e: Exception) { helper.release();if(cont.isActive) cont.resumeWithException(e) }
   }
   override fun onPrepareError(helper: DownloadHelper,error: java.io.IOException) { helper.release();if(cont.isActive) cont.resumeWithException(error) }
  })
 }
 private suspend fun downloads(factory: DataSource.Factory): DownloadManager {
  if(database==null) database=StandaloneDatabaseProvider(object: ContextWrapper(this) {
   override fun getApplicationContext(): Context=this
   override fun getDatabasePath(name: String)=File(File(filesDir,"source-lab-db").apply { mkdirs() },name)
  })
  if(cache==null) withContext(Dispatchers.IO) { cache=SimpleCache(File(filesDir,"source-lab-cache"),NoOpCacheEvictor(),database!!) }
  return DownloadManager(this,database!!,cache!!,factory,executor).also { it.maxParallelDownloads=1;it.requirements=Requirements(0);manager=it;it.resumeDownloads() }
 }
 private suspend fun downloadProof(media: Resolution.NativeMedia,factory: DataSource.Factory): JSONObject {
  nativePlayer().stop();nativePlayer().clearMediaItems();manager?.release();manager=null
  val item=MediaItem.Builder().setUri(media.uri).setMimeType(media.mimeType).build();val selected=request(item,factory)
  var dm=downloads(factory)
  // Repeated runs remove only this lab's own fixed request, not production jobs.
  dm.removeDownload(selected.id);withTimeout(10_000) { while(withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) }!=null) delay(100) }
  server!!.slow=true;dm.addDownload(selected)
  withTimeout(30_000) { while(dm.currentDownloads.none { it.request.id==selected.id && it.bytesDownloaded>4096 }) delay(100) }
  dm.setStopReason(selected.id,37);server!!.interruptNetwork()
  withTimeout(10_000) { while(dm.currentDownloads.firstOrNull { it.request.id==selected.id }?.stopReason!=37) delay(100) }
  val before=(withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) })!!.bytesDownloaded
  dm.release();manager=null;dm=downloads(factory)
  withTimeout(10_000) { while(!dm.isInitialized) delay(100) }
  require((withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) })!!.stopReason==37)
  delay(300);server!!.unavailable=false;dm.setStopReason(selected.id,Download.STOP_REASON_NONE)
  withTimeout(45_000) {
   while(true) {
    val d=withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) }
    if(d?.state==Download.STATE_COMPLETED) break
    require(d?.state!=Download.STATE_FAILED) { "Controlled download failed" };delay(100)
   }
  }
  server!!.slow=false;server!!.unavailable=true
  val requestsBefore=server!!.observed.size
  val offline=CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
  val played=advancing(selected.toMediaItem(),offline)
  require(server!!.observed.size==requestsBefore) { "Cache-only proof made an upstream request" }
  return JSONObject().put("pausedBytes",before).put("selectedStreamKeys",selected.streamKeys.size).put("stopReasonSurvivedManagerReopen",true).put("cacheOnlyPlayback",played)
 }
 fun runSuite() {
  if(job?.isActive==true) return
  web?.close();web=null
  job=scope.launch {
   val report=JSONObject().put("environment","Android controlled fixture; record actual model/API when run")
   try {
    val input=fixtures();server?.close();server=LabFixtureServer(input)
    val policy=SourcePolicy(setOf("127.0.0.1"),listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),true)
    val resolver=CatalogSourceResolver(policyFor={policy})
    suspend fun native(path: String,kind: StoredSourceKind): Resolution.NativeMedia {
     val handle=SourceHandle(TitleId("neutral"),SourceId(path),"fixture",server!!.origin+path,kind)
     return resolver.resolve(handle,ResolvePurpose.PLAYBACK) as? Resolution.NativeMedia ?: error("Fixture resolution failed")
    }
    for((path,kind) in listOf("/neutral.mp4" to StoredSourceKind.FILE,"/ts/master.m3u8" to StoredSourceKind.HLS,"/fmp4/master.m3u8" to StoredSourceKind.HLS,"/encrypted/index.m3u8" to StoredSourceKind.HLS)) {
     status.text="Testing controlled $path";val media=native(path,kind);report.put(path,advancing(MediaItem.Builder().setUri(media.uri).setMimeType(media.mimeType).build(),scopedMediaDataSource(media,fixtureLoopback=true)))
    }
    val media=native("/ts/master.m3u8",StoredSourceKind.HLS);report.put("download",downloadProof(media,scopedMediaDataSource(media,fixtureLoopback=true)))
    require(server!!.observed.all { it.second })
    report.put("headersOnEveryRequest",true).put("keyRequested",server!!.observed.any { it.first=="key.bin" }).put("initRequested",server!!.observed.any { it.first.endsWith("init.mp4") })
    withContext(Dispatchers.IO) {
     for((name,folder,extension) in listOf(Triple("ts","ts/low","ts"),Triple("fmp4","fmp4/low","m4s"))) {
      val files=File(input,folder).listFiles().orEmpty().filter { it.extension==extension }.sortedBy { it.name }
      val combined=File(cacheDir,"lab-$name.input")
      combined.outputStream().use { output ->
       if(name=="fmp4") File(input,"$folder/init.mp4").inputStream().use { it.copyTo(output) }
       files.forEach { it.inputStream().use { source -> source.copyTo(output) } }
      }
      // A failed platform extractor is a measured format limit, not a fabricated export success.
      report.put("sampleCopy-$name",runCatching { JSONObject(sampleCopySpike(combined,File(cacheDir,"lab-$name.mp4"))) }.getOrElse { error -> Log.e("EroflixP03","Controlled sample-copy $name failed",error);JSONObject().put("supported",false).put("reason",error.message?.take(160) ?: error.javaClass.simpleName) })
     }
    }
    for(name in listOf("ts","fmp4")) {
     val copy=report.getJSONObject("sampleCopy-$name")
     if(copy.optBoolean("supported")) {
      try { copy.put("playback",advancing(MediaItem.fromUri(File(cacheDir,"lab-$name.mp4").toURI().toString()),androidx.media3.datasource.DefaultDataSource.Factory(this@SourceLabActivity))) }
      catch(e: CancellationException) { throw e }
      catch(e: Exception) { copy.put("supported",false).put("reason","Muxed output failed Android video/audio playback validation");Log.e("EroflixP03","Controlled output validation $name failed",e) }
     }
    }
    report.put("passed",true);status.text="Controlled suite passed. Sample-copy limits are recorded separately."
    Log.i("EroflixP03","LAB_RESULT $report");lastResult.complete(report)
   } catch(e: CancellationException) { lastResult.completeExceptionally(e);throw e }
   catch(e: Exception) { report.put("passed",false).put("reason",e.javaClass.simpleName);status.text="Lab incomplete: ${e.javaClass.simpleName}. Required checks remain pending.";Log.e("EroflixP03","Controlled fixture failure",e);Log.i("EroflixP03","LAB_RESULT $report");lastResult.complete(report) }
   finally { player?.stop();player?.clearMediaItems();manager?.release();manager=null;cache?.release();cache=null;database?.close();database=null;server?.close();server=null }
  }
 }
 private fun probeSamples() {
  if(job?.isActive==true) return
  job=scope.launch {
   try {
    val doc=withContext(Dispatchers.IO) { assets.open("source-lab/samples.json").bufferedReader().use { JSONObject(it.readText()) } }
    val families=doc.getJSONObject("samples");val results=org.json.JSONArray();sampleChoices.removeAllViews()
    for(family in families.keys().asSequence().toList().sorted()) {
     val records=families.getJSONArray(family)
     for(index in 0 until records.length()) {
      val r=records.getJSONObject(index)
      fun nullable(name: String)=if(r.isNull(name)) null else r.optString(name).takeIf { it.isNotEmpty() }
      val handle=SourceHandle(TitleId(r.getString("titleId")),SourceId(r.getString("sourceId")),r.getString("provider"),r.getString("url"),StoredSourceKind.valueOf(r.getString("kind")),nullable("externalId"))
      status.text="Resolving $family sample ${index+1}/${records.length()} (not playback proof)"
      val resolver=CatalogSourceResolver();val result=resolver.resolve(handle,ResolvePurpose.PLAYBACK)
      val kind=when(result) { is Resolution.NativeMedia -> "native_candidate";is Resolution.WebEmbed -> "web_candidate";is Resolution.Unavailable -> result.error.code.name }
      results.put(JSONObject().put("family",family).put("index",index).put("resolution",kind))
      if(result is Resolution.WebEmbed) sampleChoices.addView(Button(this@SourceLabActivity).apply { text="$family ${index+1}: open web candidate";setOnClickListener {
       job?.cancel();player?.pause();web?.close();val policy=WebSourcePolicy.forEntry(result.entryUri)
       if(policy==null) status.text="No compiled web policy" else {
        web=ContainedWebPlayer(this@SourceLabActivity,policy,{player?.pause()},{web=null;frame.removeAllViews();frame.addView(surface)},{status.text=it.message});frame.removeAllViews();frame.addView(web)
       }
      } })
      if(result is Resolution.NativeMedia) sampleChoices.addView(Button(this@SourceLabActivity).apply { text="$family ${index+1}: test native candidate";setOnClickListener {
       job?.cancel();web?.close();web=null
       job=scope.launch { try { val measured=advancing(MediaItem.Builder().setUri(result.uri).setMimeType(result.mimeType).build(),scopedMediaDataSource(result));Log.i("EroflixP03","REAL_PLAYBACK "+JSONObject().put("family",family).put("index",index).put("measured",measured));status.text="Native sample advanced" } catch(e: Exception) { if(e is CancellationException) throw e;status.text="Native candidate did not pass" } finally { player?.stop();player?.clearMediaItems() } }
      } })
     }
    }
    Log.i("EroflixP03","SOURCE_RESOLUTIONS "+JSONObject().put("sampleGeneration",doc.getString("inputGeneration")).put("results",results));status.text="Resolution sample complete. Playback/download proofs remain separate."
   } catch(e: CancellationException) { throw e }
   catch(_: Exception) { status.text="No valid fixed sample asset. Build with explicit sourceSamplesDir to stage it." }
  }
 }
 fun openWebFixture() {
  job?.cancel();player?.pause();web?.close()
  val policy=WebSourcePolicy("https://appassets.androidplatform.net/source-lab/web/index.html","Neutral containment fixture",setOf("appassets.androidplatform.net"))
  web=ContainedWebPlayer(this,policy,{player?.pause()},{web=null;frame.removeAllViews();frame.addView(surface)},{error -> status.text=error.message},localAsset={ url ->
   val path=android.net.Uri.parse(url).path.orEmpty().removePrefix("/")
   if(!path.startsWith("source-lab/") || ".." in path) null else runCatching {
    val mime=when(path.substringAfterLast('.')) { "html" -> "text/html";"js" -> "application/javascript";"mp4" -> "video/mp4";else -> "application/octet-stream" }
    WebResourceResponse(mime,if(mime.startsWith("text") || mime.contains("javascript")) "UTF-8" else null,assets.open(path))
   }.getOrNull()
  })
  frame.removeAllViews();frame.addView(web)
 }
 fun runRealSampleSuite(familyFilter: String? = null,observeAudio: (suspend ()->JSONObject)? = null): Deferred<JSONObject> {
  job?.cancel();web?.close();web=null;frame.removeAllViews();frame.addView(surface)
  val result=CompletableDeferred<JSONObject>()
  job=scope.launch {
   try { result.complete(RealSourceProbe(this@SourceLabActivity,frame,{ media,factory ->
    if(surface.parent==null) { frame.removeAllViews();frame.addView(surface) }
    advancing(MediaItem.Builder().setUri(media.uri).setMimeType(media.mimeType).build(),factory)
   },{player?.stop();player?.clearMediaItems()},{status.text=it},observeAudio).run(familyFilter)) }
   catch(e: Exception) { result.completeExceptionally(e) }
   finally { player?.stop();player?.clearMediaItems() }
  }
  return result
 }
 fun runRealDownloadProof(): Deferred<JSONObject> {
  job?.cancel();web?.close();web=null;frame.removeAllViews();frame.addView(surface)
  val result=CompletableDeferred<JSONObject>()
  job=scope.launch {
   val ceiling=72L*1024*1024
   val report=JSONObject().put("family","tubepornclassic.com").put("index",0).put("byteCeiling",ceiling).put("completed",false)
   try {
    val raw=withContext(Dispatchers.IO) { assets.open("source-lab/samples.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("samples").getJSONArray("tubepornclassic.com").getJSONObject(0) } }
    val handle=SourceHandle(TitleId(raw.getString("titleId")),SourceId(raw.getString("sourceId")),raw.getString("provider"),raw.getString("url"),StoredSourceKind.valueOf(raw.getString("kind")))
    val media=CatalogSourceResolver().resolve(handle,ResolvePurpose.DOWNLOAD) as? Resolution.NativeMedia ?: error("Provider download did not resolve")
    val length=media.validators?.lengthBytes
    report.put("resolvedBytes",length ?: JSONObject.NULL)
    require(media.mimeType=="video/mp4" && length!=null && length>0 && length<=ceiling) { "Full provider transfer requires a known progressive MP4 within 72 MiB" }
    val factory=BudgetDataSource.Factory(scopedMediaDataSource(media),ceiling)
    val selected=DownloadRequest.Builder("source-lab-real-complete",android.net.Uri.parse(media.uri)).setMimeType(media.mimeType).build()
    val dm=downloads(factory);dm.minRetryCount=0
    dm.removeDownload(selected.id)
    withTimeout(10000) { while(withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) }!=null) delay(100) }
    status.text="Testing full provider download (maximum 72 MiB)";dm.addDownload(selected)
    val began=android.os.SystemClock.elapsedRealtime();var lastProgress=0L
    val completed=withTimeout(540000) {
     while(true) {
      val state=withContext(Dispatchers.IO) { dm.downloadIndex.getDownload(selected.id) }
      val elapsed=android.os.SystemClock.elapsedRealtime()-began
      if(elapsed-lastProgress>=5000) {
       report.put("progressBytes",state?.bytesDownloaded ?: 0L).put("transferElapsedMs",elapsed)
       val snapshot=report.toString()
       withContext(Dispatchers.IO) { File(filesDir,"source-lab-download.json").writeText(snapshot) }
       lastProgress=elapsed
      }
      if(state?.state==Download.STATE_COMPLETED) return@withTimeout state
      check(state?.state!=Download.STATE_FAILED) { "Provider transfer failed" };delay(200)
     }
     @Suppress("UNREACHABLE_CODE") error("No completed provider transfer")
    }
    report.put("downloadedBytes",completed.bytesDownloaded).put("networkMediaBytes",factory.consumed)
    check(completed.bytesDownloaded==length) { "Completed provider transfer length differs from the resolved resource" }
    val before=factory.consumed
    val offline=CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
    report.put("cacheOnlyPlayback",advancing(selected.toMediaItem(),offline))
    val p=nativePlayer();check(p.duration>5000) { "Provider output duration is unknown or too short" }
    val midpoint=p.duration/2;val previousFrames=p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
    p.seekTo(midpoint);p.play()
    withTimeout(20000) { while(p.currentPosition<midpoint+1000 || (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0)<=previousFrames) {
     p.playerError?.let { error("Cache-only provider seek failed") };delay(100)
    } }
    p.pause();report.put("offlineMidpointMs",p.currentPosition).put("durationMs",p.duration)
    check(factory.consumed==before) { "Offline provider proof used an upstream" }
    report.put("upstreamBytesDuringOffline",factory.consumed-before).put("completed",true)
    status.text="Full provider download and cache-only seek passed"
   } catch(e: CancellationException) { report.put("completed",false).put("reason","Cancelled or transfer deadline exceeded");throw e }
   catch(e: Exception) { report.put("completed",false).put("reason",e.message?.take(160) ?: e.javaClass.simpleName);Log.e("EroflixP03","Real download proof failed",e) }
   finally {
    withContext(NonCancellable+Dispatchers.IO) { File(filesDir,"source-lab-download.json").writeText(report.toString()) }
    player?.stop();player?.clearMediaItems();manager?.release();manager=null;cache?.release();cache=null;database?.close();database=null
    if(!result.isCompleted) result.complete(report)
   }
  }
  return result
 }
 override fun onPause() { job?.cancel();player?.pause();web?.pause();super.onPause() }
 override fun onDestroy() { job?.cancel();scope.cancel();web?.close();player?.release();manager?.release();cache?.release();database?.close();server?.close();executor.shutdownNow();super.onDestroy() }
}
