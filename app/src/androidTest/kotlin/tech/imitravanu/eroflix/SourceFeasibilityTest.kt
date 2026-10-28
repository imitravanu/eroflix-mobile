package tech.imitravanu.eroflix

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import tech.imitravanu.eroflix.core.network.CatalogSourceResolver
import tech.imitravanu.eroflix.model.*

/** Compiled now; run explicitly with -PsourceLab=true on an authorized API37 phone. */
@RunWith(AndroidJUnit4::class)
class SourceFeasibilityTest {
 @get:Rule val activity=ActivityScenarioRule(SourceLabActivity::class.java)
 @Test fun applicationUsesTheRealSourceResolver()=runBlocking {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EroflixApplication
  val provision=withTimeout(15000) { app.provision.filterIsInstance<ProvisionState.Ready>().first() }
  assertTrue(provision.container.resolver is CatalogSourceResolver)
  val invalid=SourceHandle(TitleId("neutral"),SourceId("neutral"),"fixture","file:///neutral.mp4",StoredSourceKind.FILE)
  assertEquals(ErrorCode.UNSUPPORTED,(provision.container.resolver.resolve(invalid,ResolvePurpose.PLAYBACK) as Resolution.Unavailable).error.code)
 }
 @Test fun nativeHlsProgressiveSelectedDownloadResumeAndOfflineProof()=runBlocking {
  var result: Deferred<JSONObject>?=null
  activity.scenario.onActivity { result=it.lastResult;it.runSuite() }
  val measured=withTimeout(180_000) { result!!.await() }
  assertTrue(measured.toString(),measured.getBoolean("passed"))
  assertTrue(measured.getBoolean("headersOnEveryRequest"));assertTrue(measured.getBoolean("keyRequested"));assertTrue(measured.getBoolean("initRequested"))
  assertTrue(measured.getJSONObject("download").getBoolean("stopReasonSurvivedManagerReopen"))
  assertTrue(measured.getJSONObject("download").getJSONObject("cacheOnlyPlayback").getLong("positionMs")>=2000)
  // Export spikes report their own supported/unsupported evidence; not a fake remux gate.
  assertTrue(measured.has("sampleCopy-ts"));assertTrue(measured.has("sampleCopy-fmp4"))
 }
 @Test fun webRendererExitIsHandledAndFileContentWindowsAreDisabled()=runBlocking {
  var view: WebView?=null
  activity.scenario.onActivity { a -> a.openWebFixture();view=a.containedWeb!!.getChildAt(a.containedWeb!!.childCount-1) as WebView
   assertFalse(view!!.settings.allowFileAccess);assertFalse(view!!.settings.allowContentAccess);assertFalse(view!!.settings.javaScriptCanOpenWindowsAutomatically)
  }
  withTimeout(15_000) { while(true) {
   var ready=false;activity.scenario.onActivity { ready=view!!.progress==100 && view!!.webViewRenderProcess!=null }
   if(ready) break;delay(100)
  } }
  activity.scenario.onActivity { assertTrue(view!!.webViewRenderProcess!!.terminate()) }
  withTimeout(10_000) { while(true) {
   var destroyed=false;activity.scenario.onActivity { destroyed=view!!.parent==null }
   if(destroyed) break;delay(100)
  } }
 }
 private suspend fun evaluate(view: WebView,script: String): String {
  val reply=CompletableDeferred<String>()
  activity.scenario.onActivity { view.evaluateJavascript(script) { reply.complete(it) } }
  return withTimeout(5000) { reply.await() }
 }
 private suspend fun tap(view: WebView,id: String) {
  android.util.Log.i("EroflixP03","WEB_ACTION $id")
  val raw=evaluate(view,"(()=>{const e=document.getElementById('$id');if(!e)return null;e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()")
  assertNotEquals("Fixture control $id is missing from the loaded page","null",raw)
  val rect=org.json.JSONArray(raw);val location=IntArray(2);var width=0;var height=0
  activity.scenario.onActivity { view.getLocationOnScreen(location);width=view.width;height=view.height }
  val scale=width/rect.getDouble(2);val localX=rect.getDouble(0)*scale;val localY=rect.getDouble(1)*scale
  assertTrue("Fixture control $id is outside its WebView",localX>=0 && localY>=0 && localX<width && localY<height)
  val x=location[0]+localX.toFloat();val y=location[1]+localY.toFloat()
  val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;val now=SystemClock.uptimeMillis()
  for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
   val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
   try { require(automation.injectInputEvent(event,true)) } finally { event.recycle() }
  }
 }
 private fun activeOwnAudio(): Int {
  val uid=InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.uid
  val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio")
  val dump=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  return dump.lineSequence().count { "AudioPlaybackConfiguration piid:" in it && "u/pid:$uid/" in it && "state:started" in it }
 }
 @Test fun webAbuseActionsStayContainedAndClosingReleasesPlayingView()=runBlocking {
  lateinit var view: WebView
  activity.scenario.onActivity { a -> a.openWebFixture();view=a.containedWeb!!.getChildAt(a.containedWeb!!.childCount-1) as WebView }
  withTimeout(15000) { while(evaluate(view,"location.pathname === '/source-lab/web/index.html' && document.readyState === 'complete' && document.getElementById('file') !== null")!="true") delay(100) }
  activity.scenario.onActivity { assertTrue("The initial web request lost its provider Referer",it.containedWeb!!.initialRefererObserved) }
  val original=evaluate(view,"location.href")
  for(id in listOf("popup","intent","navigate","download","permission","file")) {
   tap(view,id);delay(200)
   assertEquals("The web action $id navigated outside its source",original,evaluate(view,"location.href"))
   activity.scenario.onActivity { a -> assertEquals(a,view.context);assertNotNull(view.parent) }
  }
  tap(view,"play")
  withTimeout(15000) { while(evaluate(view,"v.currentTime > 1 && !v.paused")!="true") delay(100) }
  assertTrue("The playing fixture has no active app audio",activeOwnAudio()>0)
  activity.scenario.onActivity { a ->
   (a.containedWeb!!.getChildAt(1) as android.widget.Button).performClick()
   assertNull(view.parent);assertNull(a.containedWeb)
  }
  withTimeout(5000) { while(activeOwnAudio()!=0) delay(100) }
  android.util.Log.i("EroflixP03","WEB_CONTAINMENT_RESULT "+JSONObject().put("blockedActions",6).put("videoAdvancedBeforeExit",true).put("viewDestroyedOnExit",true).put("noResidualAppAudio",true))
  Unit
 }
}
