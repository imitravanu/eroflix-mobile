package tech.imitravanu.eroflix

import android.content.*
import android.media.*
import android.os.*
import android.view.*
import android.webkit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.core.network.WebSourcePolicy
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*
import java.io.*

/** Actual MainActivity/Shell and sole production service, entirely neutral isolated data. */
@RunWith(AndroidJUnit4::class)
class MainPlayerIntegrationTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun waitFor(predicate: ()->Boolean)=ui.waitUntil(20_000,predicate)
    private fun copyAssets(path: String,out: File) {
        val names=context.assets.list(path).orEmpty()
        if(names.isEmpty()) { out.parentFile!!.mkdirs();context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
        else { out.mkdirs();names.forEach { copyAssets("$path/$it",File(out,it)) } }
    }
    private fun <T: View> find(view: View,type: Class<T>): T? {
        if(type.isInstance(view)) return type.cast(view)
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),type)?.let { return it }
        return null
    }
    private fun activeOwnAudio(): Int {
        val dump=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys audio")).bufferedReader().use { it.readText() }
        val uid=context.applicationInfo.uid
        return dump.lineSequence().count { "AudioPlaybackConfiguration piid:" in it && "u/pid:$uid/" in it && "state:started" in it }
    }
    @Test fun actualMainRouteFocusNoisyAutoPipActionsAndNativeWebCleanup() {
        val app=context.applicationContext as EroflixApplication
        waitFor { app.playbackOrNull!=null }
        val original=app.playback;val originalContainer=app.container
        main { original.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        waitFor { original.surfacePlayer.value==null }
        val root=File(context.cacheDir,"p05-main-${System.nanoTime()}");copyAssets("source-lab",root)
        val server=LabFixtureServer(root)
        val checkpoint="p05-main-${System.nanoTime()}"
        val personal=FixturePersonalRepository()
        val title=fixtureTitles.first()
        val native=SourceHandle(title.id,SourceId("p05-main-native"),"Neutral native","${server.origin}/neutral.mp4",StoredSourceKind.FILE)
        val web=SourceHandle(title.id,SourceId("p05-main-web"),"Neutral web","https://fixture.invalid/index.html",StoredSourceKind.EMBED)
        val base=FixtureCatalogRepository(personal)
        val catalog=object: CatalogRepository by base {
            override suspend fun details(id: TitleId): Outcome<TitleDetails> = if(id==title.id) Outcome.Success(TitleDetails(title,"Neutral lifecycle validation",listOf(native,web),emptyList(),"Disposable fixture")) else base.details(id)
        }
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution = if(source.id==web.id)
                Resolution.WebEmbed(web.originalUrl,"fixture","Neutral contained web; no native quality or download.")
            else Resolution.NativeMedia(source.originalUrl,"video/mp4",listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),null,source.id.value,null,emptyList(),MediaCapabilities(true,false,false))
        }
        val owner=ProductionPlayback(context,catalog,personal,resolver,true,checkpoint,
            webPolicyOverride={ WebSourcePolicy(it.entryUri,"Neutral provider",setOf("fixture.invalid")) },
            webResourceOverride={ url ->
                val path=android.net.Uri.parse(url).path.orEmpty()
                val data=when(path) {
                    "/index.html" -> """<!doctype html><meta name="viewport" content="width=device-width"><button id="play" onclick="document.querySelector('video').play()">Play neutral web</button><video controls playsinline src="/neutral.mp4" width="240"></video>""".toByteArray()
                    "/neutral.mp4" -> File(root,"neutral.mp4").readBytes()
                    else -> byteArrayOf()
                }
                WebResourceResponse(if(path.endsWith("mp4")) "video/mp4" else "text/html","UTF-8",ByteArrayInputStream(data))
            })
        val audio=context.getSystemService(AudioManager::class.java)
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val observations=java.util.Collections.synchronizedList(mutableListOf<PlaybackObservation>())
        val observeScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        main { observeScope.launch { owner.observations.collect { observations.add(it) } } }
        var focus: AudioFocusRequest?=null
        var scenario: ActivityScenario<MainActivity>?=null
        try {
            main { app.playback=owner;UiValidationContainer.value=originalContainer.copy(catalog=catalog,personal=personal,resolver=resolver,player=owner,fixture=true) }
            val rejected=PlaybackService.rejectedStarts.get()
            main { UiValidationContainer.serviceReady=false;context.startService(Intent(context,PlaybackService::class.java)) }
            waitFor { PlaybackService.rejectedStarts.get()>rejected }
            main { assertNull(owner.surfacePlayer.value);UiValidationContainer.serviceReady=true }
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            waitFor { ui.onAllNodesWithTag("title-${title.id.value}").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("title-${title.id.value}").performClick()
            ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag("play-${native.id.value}"))
            ui.onNodeWithTag("play-${native.id.value}").performClick()
            waitFor { check(owner.ui.value.error==null) { owner.ui.value.error.toString() };owner.ui.value.phase==PlaybackPhase.PLAYING && owner.ui.value.positionMs>500 }
            // Actual observation segments must not bridge seeks or mix speed contexts.
            main { owner.setSpeed(1f) };Thread.sleep(1100)
            main { owner.setSpeed(2f) };Thread.sleep(1100)
            main { owner.setSpeed(1f) };Thread.sleep(200)
            waitFor { synchronized(observations) { observations.any { it.playedFromMs!=null && it.speed==1f } && observations.any { it.playedFromMs!=null && it.speed==2f } } }
            main { owner.seekTo(6000);owner.seekTo(1000) };Thread.sleep(250)
            assertTrue(synchronized(observations) { observations.filter { it.playedFromMs!=null }.none { it.playedFromMs!!<4000 && it.positionMs>=6000 } })
            // Real platform focus request, no carrier call and no system volume increase.
            main {
                focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener({},Handler(Looper.getMainLooper())).build()
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(focus!!))
            }
            waitFor { owner.ui.value.phase==PlaybackPhase.PAUSED }
            main { audio.abandonAudioFocusRequest(focus!!);focus=null }
            // Resume policy remains explicit; transient auto-resume is permitted only in visible route.
            main { owner.play() };waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
            // AUDIO_BECOMING_NOISY is a protected system broadcast: shell injection is denied.
            // Physical unplug/Bluetooth remains a separate acceptance check.
            main { owner.pause() };waitFor { owner.ui.value.phase==PlaybackPhase.PAUSED }
            // Settled pause must not keep writing progress/revisions every five seconds.
            Thread.sleep(300)
            val pausedRevision=personal.snapshot.value.revision
            Thread.sleep(10_500)
            assertEquals(pausedRevision,personal.snapshot.value.revision)
            main { owner.play() };waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor { var pip=false;scenario!!.onActivity { pip=it.isInPictureInPictureMode };pip }
            scenario!!.onActivity { activity ->
                activity.enterPlayerPip()
                val params=activity.configuredPipParams!!
                assertEquals(2,params.actions.size)
                params.actions.first().actionIntent.send() // Pause actual production RemoteAction.
            }
            waitFor { owner.ui.value.phase==PlaybackPhase.PAUSED }
            scenario!!.onActivity { it.configuredPipParams!!.actions[1].actionIntent.send() }
            waitFor { owner.ui.value.positionMs>=7000 }
            main { owner.seekTo(1000) }
            scenario!!.onActivity { it.configuredPipParams!!.actions.first().actionIntent.send() }
            waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
            main { owner.lock(true) }
            waitFor { scenario!!.state==androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertEquals(PlaybackPhase.PAUSED,owner.ui.value.phase)
            assertTrue(owner.ui.value.locked)
            scenario!!.close();scenario=null
            main { owner.lock(false) }
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            waitFor { ui.onAllNodesWithTag("title-${title.id.value}").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(owner.ui.value.phase==PlaybackPhase.PLAYING)
            ui.onNodeWithTag("title-${title.id.value}").performClick()
            ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag("play-${native.id.value}"))
            ui.onNodeWithTag("play-${native.id.value}").performClick()
            waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
            ui.onNodeWithText("Choose source").performScrollTo().performClick()
            ui.onNodeWithText("${web.provider} · ${web.kind}").performClick()
            var webView: WebView?=null
            waitFor { scenario!!.onActivity { webView=find(it.window.decorView,WebView::class.java) };webView!=null }
            main { assertFalse(owner.surfacePlayer.value?.isPlaying ?: false) }
            ui.onNodeWithText(title.title).performScrollTo()
            // Actual contained web tap after document readiness. Browser output remains a separate claim.
            var webReady=false
            waitFor { main { webView!!.evaluateJavascript("location.href==='https://fixture.invalid/index.html' && document.readyState==='complete' && !!document.querySelector('#play') && !!document.querySelector('video')") { raw -> webReady=raw=="true" } };webReady }
            var webTapDispatched=false
            waitFor {
                main { webView!!.evaluateJavascript("(()=>{const b=document.querySelector('#play');if(!b)return null;const r=b.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:r.width,h:r.height,vw:innerWidth}})()") { raw ->
                    val box=runCatching { org.json.JSONTokener(raw).nextValue() as? org.json.JSONObject }.getOrNull()
                    if(!webTapDispatched && box!=null && box.optDouble("w")>=8 && box.optDouble("h")>=8 && box.optDouble("vw")>0) {
                        val scale=webView!!.width/box.getDouble("vw")
                        val x=box.getDouble("x").toFloat()*scale.toFloat();val y=box.getDouble("y").toFloat()*scale.toFloat()
                        if(x>=0 && x<webView!!.width && y>=0 && y<webView!!.height) {
                            webTapDispatched=true
                            val now=SystemClock.uptimeMillis()
                            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) MotionEvent.obtain(now,now,action,x,y,0).also { it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;webView!!.dispatchTouchEvent(it);it.recycle() }
                        }
                    }
                } };webTapDispatched
            }
            var advanced=false
            var webPosition=0.0
            waitFor { main { webView!!.evaluateJavascript("(()=>{const v=document.querySelector('video');return JSON.stringify({t:v?v.currentTime:0,paused:v?v.paused:true,muted:v?v.muted:true,volume:v?v.volume:0,frames:v&&v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().totalVideoFrames:0})})()") { raw ->
                val text=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                val value=text?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                if(value!=null) {
                    webPosition=value.optDouble("t")
                    advanced=webPosition>1 && !value.optBoolean("paused",true) && !value.optBoolean("muted",true) && value.optDouble("volume")>0 && value.optInt("frames")>0
                }
            } };advanced }
            waitFor { activeOwnAudio()>0 }
            val beforeAudio=activeOwnAudio()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor { owner.ui.value.web==null }
            waitFor { activeOwnAudio()==0 }
            main { assertNull(webView!!.parent) }
            android.util.Log.i("EroflixP05","WEB neutralPositionSeconds=$webPosition video=advanced unmuted=true activeOwnAudioBefore=$beforeAudio activeOwnAudioAfter=0 detached=true")
            main { assertFalse(owner.surfacePlayer.value?.isPlaying ?: false) }
            assertEquals(volume,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            android.util.Log.i("EroflixP05","MAIN nativeRoute=passed focusTransient=paused physicalNoisy=pending autoPip=entered remoteToggle=passed lock=paused relaunch=noAutoPlay webTransition=nativeStopped webBackground=closed")
        } finally {
            observeScope.cancel()
            focus?.let { audio.abandonAudioFocusRequest(it) }
            scenario?.close()
            main { owner.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
            waitFor { owner.surfacePlayer.value==null }
            main { owner.dispose();app.playback=original;UiValidationContainer.value=null;UiValidationContainer.serviceReady=true }
            server.close();root.deleteRecursively();context.deleteSharedPreferences(checkpoint)
            assertEquals(volume,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }
}
