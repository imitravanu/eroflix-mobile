package tech.imitravanu.eroflix

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*
import java.io.File

/** Human disconnect only after BT_READY; never changes Bluetooth/radio/volume settings. */
@RunWith(AndroidJUnit4::class)
class ProductionBluetoothDisconnectTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val bluetoothTypes=setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_BLE_SPEAKER)
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun copyAssets(path: String,out: File) {
        val names=context.assets.list(path).orEmpty()
        if(names.isEmpty()) { out.parentFile!!.mkdirs();context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
        else { out.mkdirs();names.forEach { copyAssets("$path/$it",File(out,it)) } }
    }
    private fun activeOwnAudio(): Int {
        val text=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys audio")).bufferedReader().use { it.readText() }
        val uid=context.applicationInfo.uid
        return text.lineSequence().count { "AudioPlaybackConfiguration piid:" in it && "u/pid:$uid/" in it && "state:started" in it }
    }
    @Test fun physicalHeadsetDisconnectPausesProductionPlayerWithoutAutoResume() {
        Assume.assumeTrue("Explicit human Bluetooth disconnect opt-in required",InstrumentationRegistry.getArguments().getString("allowBluetoothDisconnect")=="true")
        val audio=context.getSystemService(AudioManager::class.java)
        val connected=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in bluetoothTypes }
        Assume.assumeTrue("INCONCLUSIVE: no connected Bluetooth media output",connected.isNotEmpty())
        val app=context.applicationContext as EroflixApplication
        ui.waitUntil(30_000) { app.playbackOrNull!=null }
        val original=app.playback
        main { original.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        ui.waitUntil(10_000) { original.surfacePlayer.value==null }
        val root=File(context.cacheDir,"p05-bt-${System.nanoTime()}");copyAssets("source-lab",root)
        val server=LabFixtureServer(root)
        val prefs="p05-bt-${System.nanoTime()}"
        val personal=FixturePersonalRepository();val catalog=FixtureCatalogRepository(personal)
        val source=SourceHandle(fixtureTitles.first().id,SourceId("p05-bt-native"),"Neutral fixture","${server.origin}/neutral.mp4",StoredSourceKind.FILE)
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution = Resolution.NativeMedia(source.originalUrl,"video/mp4",listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),null,source.id.value,null,emptyList(),MediaCapabilities(true,false,false))
        }
        val owner=ProductionPlayback(context,catalog,personal,resolver,true,prefs)
        var scenario: ActivityScenario<ProductionPlayerHarnessActivity>?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var ready=false
        try {
            main {
                app.playback=owner;ProductionHarness.owner=owner;ProductionHarness.catalog=catalog
                scope.launch { owner.surfacePlayer.collect { it?.repeatMode=Player.REPEAT_MODE_ONE } }
            }
            scenario=ActivityScenario.launch(Intent(context,ProductionPlayerHarnessActivity::class.java))
            runBlocking { withContext(Dispatchers.Main) { assertTrue(owner.open(source) is Outcome.Success) } }
            ui.waitUntil(20_000) { check(owner.ui.value.error==null) { owner.ui.value.error.toString() };owner.ui.value.phase==PlaybackPhase.PLAYING && owner.ui.value.positionMs>500 }
            // Observe only the currently playing AudioTrack created by the production sink.
            var routeType: Int?=null
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(routeType==null && android.os.SystemClock.elapsedRealtime()<deadline) {
                routeType=owner.observedAudioRouteType()?.takeIf { it in bluetoothTypes }
                if(routeType==null) Thread.sleep(100)
            }
            val own=activeOwnAudio()
            if(routeType==null || own!=1) {
                android.util.Log.i("EroflixP05","BT_INCONCLUSIVE connectedMediaOutput=true routedConfigExposed=${routeType!=null} ownActiveAudio=$own noHumanDisconnectRequested=true")
                Assume.assumeTrue("INCONCLUSIVE: active Bluetooth route or singular own audio not observable",false)
            }
            ready=true
            android.util.Log.i("EroflixP05","BT_READY routeType=$routeType productionSinkRoute=true ownActiveAudio=$own volumeChangedByTest=false waitingPhysicalDisconnectSeconds=90")
            ui.waitUntil(90_000) { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.id in connected.map { before -> before.id } } }
            ui.waitUntil(10_000) { owner.ui.value.phase==PlaybackPhase.PAUSED }
            ui.waitUntil(10_000) { activeOwnAudio()==0 }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(3000)
            main { assertFalse(owner.surfacePlayer.value?.isPlaying ?: false) }
            assertEquals(0,activeOwnAudio())
            android.util.Log.i("EroflixP05","BT_DISCONNECTED physical=true playerPaused=true ownAudio=0 returnAutoResume=false volumeChangedByTest=false")
        } finally {
            scope.cancel();scenario?.close()
            main { owner.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
            ui.waitUntil(10_000) { owner.surfacePlayer.value==null }
            main { owner.dispose();app.playback=original;ProductionHarness.owner=null;ProductionHarness.catalog=null }
            server.close();root.deleteRecursively();context.deleteSharedPreferences(prefs)
            if(!ready) android.util.Log.i("EroflixP05","BT_CLEANUP readyWasNotEmitted=true settingsUnchanged=true")
        }
    }
}
