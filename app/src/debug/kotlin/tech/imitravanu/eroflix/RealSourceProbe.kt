package tech.imitravanu.eroflix

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.imitravanu.eroflix.core.network.*
import tech.imitravanu.eroflix.core.media.scopedMediaDataSource
import tech.imitravanu.eroflix.model.*
import java.io.File
import java.io.IOException

/** Dated, fixed-denominator device experiment. Never a production background scan. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class RealSourceProbe(private val activity: Activity,private val frame: FrameLayout,
 private val native: suspend (Resolution.NativeMedia,DataSource.Factory)->JSONObject,
 private val stopNative: ()->Unit,private val progress: (String)->Unit,
 private val observeAudio: (suspend ()->JSONObject)? = null) {
 private suspend fun evaluate(view: WebView,js: String): String {
  val result=CompletableDeferred<String>();view.evaluateJavascript(js) { result.complete(it) }
  return withTimeout(3000) { result.await() }
 }
 private suspend fun web(result: Resolution.WebEmbed): JSONObject {
  val policy=WebSourcePolicy.forEntry(result.entryUri) ?: return JSONObject().put("outcome","NO_COMPILED_POLICY")
  stopNative();var failure=false;var gestures=0
  val blocked=java.util.Collections.synchronizedSet(mutableSetOf<String>())
  val wrapper=ContainedWebPlayer(activity,policy,stopNative,{}, {failure=true},onBlockedHost={ synchronized(blocked) { if(blocked.size<32) blocked+=it } })
  frame.removeAllViews();frame.addView(wrapper)
  val view=wrapper.getChildAt(wrapper.childCount-1) as WebView
  try {
   val played=withTimeoutOrNull(12000) {
    var lastGesture=0L
    while(true) {
     val selector=JSONObject.quote(policy.playerContainerSelector)
     val raw=evaluate(view,"(()=>{const root=document.querySelector($selector);const v=root?root.querySelector('video'):null;const buttons=root?Array.from(root.querySelectorAll('button,[role=button]')):[];const needsSound=v&&(v.muted||v.volume===0);const b=buttons.find(e=>(needsSound?/^unmute$/i:/^(play|play video)$/i).test((e.getAttribute('aria-label')||e.textContent||'').trim())&&e.getBoundingClientRect().width>=8);const e=b||v;if(e&&(!v||v.paused||needsSound))e.scrollIntoView({block:'center'});const r=e?e.getBoundingClientRect():null;return {t:v?v.currentTime:0,paused:v?v.paused:true,muted:v?v.muted:true,volume:v?v.volume:0,audioDecodedBytes:v?v.webkitAudioDecodedByteCount||0:0,frames:v&&v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().totalVideoFrames:0,x:r?r.x+r.width/2:-1,y:r?r.y+r.height/2:-1,w:r?r.width:0,h:r?r.height:0,viewportWidth:innerWidth,viewportHeight:innerHeight}})()")
     val doc=JSONObject(raw)
     if(doc.optDouble("t")>1 && !doc.optBoolean("paused",true) && doc.optInt("frames")>0 && (!doc.optBoolean("muted",true) && doc.optDouble("volume")>0 || gestures>=3)) return@withTimeoutOrNull doc
     val now=SystemClock.uptimeMillis();val cssX=doc.optDouble("x",-1.0);val cssY=doc.optDouble("y",-1.0)
     if((doc.optBoolean("paused",true) || doc.optBoolean("muted",true) || doc.optDouble("volume")==0.0) && gestures<3 && now-lastGesture>=1500 && doc.optDouble("w")>=8 && doc.optDouble("h")>=8 && cssX>=0 && cssY>=0 && cssX<doc.optDouble("viewportWidth") && cssY<doc.optDouble("viewportHeight")) {
      val scale=view.width/doc.getDouble("viewportWidth");val x=(cssX*scale).toFloat();val y=(cssY*scale).toFloat()
      if(y>=view.height) { delay(400);continue }
      for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
       val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
       try { view.dispatchTouchEvent(event) } finally { event.recycle() }
      };gestures++;lastGesture=now
     }
     if(failure) return@withTimeoutOrNull null
     delay(400)
    }
    @Suppress("UNREACHABLE_CODE") null
   }
   val audio=if(played!=null) observeAudio?.invoke() else null
   val audioProof=played!=null && !played.optBoolean("muted",true) && played.optDouble("volume")>0 && (audio?.optInt("activeOwnAudio") ?: 0)>0
   return JSONObject().put("outcome",if(played==null) "NO_ADVANCING_ACCESSIBLE_VIDEO" else "VIDEO_ADVANCED")
    .put("measurement",played ?: JSONObject()).put("audioProof",if(audioProof) "OWN_APP_AUDIO_ACTIVE_WITH_UNMUTED_VIDEO" else "NOT_PROVEN").put("audioObservation",audio ?: JSONObject()).put("playGestures",gestures)
    .put("initialRefererObserved",wrapper.initialRefererObserved)
    .put("playerContainer",policy.playerContainerSelector).put("contentIdentity","NOT_VERIFIED")
    .put("blockedHosts",JSONArray(synchronized(blocked) { blocked.toList().sorted() }))
    .put("blockedRequests",JSONArray(wrapper.blockedRequests.map { block -> JSONObject().put("host",block.host).put("scheme",block.scheme).put("method",block.method).put("route",block.route) }))
  } finally { wrapper.close();frame.removeView(wrapper) }
 }
 suspend fun run(familyFilter: String? = null): JSONObject {
  val input=withContext(Dispatchers.IO) { activity.assets.open("source-lab/samples.json").bufferedReader().use { JSONObject(it.readText()) } }
  val families=input.getJSONObject("samples");val cases=JSONArray()
  require(familyFilter==null || families.has(familyFilter)) { "Unknown fixed sample family" }
  val selected=families.keys().asSequence().toList().sorted().filter { familyFilter==null || it==familyFilter }
  val report=JSONObject().put("inputGeneration",input.getString("inputGeneration")).put("familyFilter",familyFilter ?: JSONObject.NULL).put("cases",cases)
  suspend fun save()=withContext(Dispatchers.IO) { File(activity.filesDir,"source-lab-real.json").writeText(report.toString()) }
  for(family in selected) {
   val records=families.getJSONArray(family)
   for(index in 0 until records.length()) {
    val raw=records.getJSONObject(index)
    val handle=SourceHandle(TitleId(raw.getString("titleId")),SourceId(raw.getString("sourceId")),raw.getString("provider"),raw.getString("url"),StoredSourceKind.valueOf(raw.getString("kind")))
    val entry=JSONObject().put("family",family).put("index",index).put("nativePlayback","NOT_ATTEMPTED").put("webPlayback","NOT_ATTEMPTED").put("completedDownload","NOT_ATTEMPTED")
    progress("Real source $family ${index+1}/${records.length()}")
    try {
     val nativeBlocked=linkedSetOf<String>()
     val resolver=CatalogSourceResolver(onBlockedHost={ synchronized(nativeBlocked) { if(nativeBlocked.size<16) nativeBlocked+=it } })
     val playback=withTimeoutOrNull(15000) { resolver.resolve(handle,ResolvePurpose.PLAYBACK) }
     entry.put("playbackResolution",resolutionLabel(playback))
     if(playback is Resolution.Unavailable) entry.put("playbackReason",playback.error.message)
     when(playback) {
      is Resolution.NativeMedia -> {
       val factory=BudgetDataSource.Factory(scopedMediaDataSource(playback),5L*1024*1024)
       val measured=try { withTimeoutOrNull(15000) { native(playback,factory) } }
        catch(e: Exception) { if(e is CancellationException) throw e;entry.put("nativeFailure",if(e is IOException) "NETWORK_OR_BYTE_BUDGET" else e.javaClass.simpleName);null }
       entry.put("nativePlayback",if(measured!=null) "VIDEO_AUDIO_ADVANCED" else "FAILED_OR_DEADLINE").put("nativeMeasurement",measured ?: JSONObject()).put("nativeBytesRead",factory.consumed)
       stopNative()
      }
      is Resolution.WebEmbed -> entry.put("webPlayback",web(playback))
      else -> Unit
     }
     // Independent purpose result. Full real videos are not silently downloaded over cellular.
     val download=withTimeoutOrNull(15000) { resolver.resolve(handle,ResolvePurpose.DOWNLOAD) }
     entry.put("downloadResolution",resolutionLabel(download))
     if(download is Resolution.Unavailable) entry.put("downloadReason",download.error.message)
     if(download is Resolution.NativeMedia) entry.put("completedDownload","PENDING_FULL_PROVIDER_TRANSFER")
     entry.put("nativeBlockedHosts",JSONArray(synchronized(nativeBlocked) { nativeBlocked.toList().sorted() }))
    } catch(e: CancellationException) { throw e }
    catch(e: Exception) { entry.put("caseFailure",e.javaClass.simpleName) }
    finally { stopNative() }
    cases.put(entry);save();android.util.Log.i("EroflixP03","REAL_SAMPLE $entry")
   }
  }
  report.put("attempted",cases.length());save();return report
 }
 private fun resolutionLabel(r: Resolution?)=when(r) {
  null -> "DEADLINE";is Resolution.NativeMedia -> "NATIVE_CANDIDATE";is Resolution.WebEmbed -> "WEB_CANDIDATE";is Resolution.Unavailable -> r.error.code.name
 }
}

/** Shared byte ceiling includes playlist/segment reads across concurrent data sources. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class BudgetDataSource(private val delegate: DataSource,private val owner: Factory): DataSource by delegate {
 override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
  val grant=owner.reserve(length);if(grant==0) throw IOException("Source experiment byte budget exhausted")
  return try { val read=delegate.read(buffer,offset,grant);owner.refund(grant-maxOf(0,read));read }
   catch(e: Exception) { owner.refund(grant);throw e }
 }
 @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
 class Factory(private val upstream: DataSource.Factory,private val limit: Long): DataSource.Factory {
  private var reserved=0L
  val consumed: Long @Synchronized get()=reserved
  @Synchronized fun reserve(requested: Int): Int=minOf(requested.toLong(),limit-reserved).toInt().also { reserved+=it }
  @Synchronized fun refund(bytes: Int) { reserved-=bytes }
  override fun createDataSource(): DataSource=BudgetDataSource(upstream.createDataSource(),this)
 }
}
