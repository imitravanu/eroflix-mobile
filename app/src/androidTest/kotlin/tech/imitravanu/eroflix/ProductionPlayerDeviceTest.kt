package tech.imitravanu.eroflix

import android.content.Intent
import android.media.AudioManager
import android.os.Debug
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.testing.*
import tech.imitravanu.eroflix.feature.player.PlayerCropMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ProductionPlayerDeviceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(timeout: Long=15_000,predicate: ()->Boolean) { ui.waitUntil(timeout,predicate) }
    private fun main(block: ()->Unit) { InstrumentationRegistry.getInstrumentation().runOnMainSync(block) }
    private fun copyAssets(path: String,out: File) {
        val names=context.assets.list(path).orEmpty()
        if(names.isEmpty()) { out.parentFile!!.mkdirs();context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
        else { out.mkdirs();names.forEach { copyAssets("$path/$it",File(out,it)) } }
    }
    @Test fun productionServiceNativeFormatsTracksControlsRecreationAndExit() {
        val app=context.applicationContext as EroflixApplication
        waitFor(30_000) { app.playbackOrNull!=null }
        val original=app.playback
        main { original.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
        waitFor { original.surfacePlayer.value==null }
        val root=File(context.cacheDir,"p05-neutral-${System.nanoTime()}")
        copyAssets("source-lab",root)
        val server=LabFixtureServer(root)
        val personal=FixturePersonalRepository()
        val catalog=FixtureCatalogRepository(personal)
        val resolver=object: SourceResolver {
            override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution {
                if(source.id.value=="p05-late") withContext(NonCancellable) { delay(250) }
                return Resolution.NativeMedia(source.originalUrl,
                if(source.kind==StoredSourceKind.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
                listOf(RequestScope(setOf("127.0.0.1"),mapOf("Referer" to "https://fixture.invalid/"))),null,source.id.value,"Neutral P05 fixture",emptyList(),MediaCapabilities(true,false,false))
            }
        }
        val checkpointName="p05-neutral-${System.nanoTime()}"
        val owner=ProductionPlayback(context,catalog,personal,resolver,fixtureLoopback=true,checkpointName=checkpointName)
        val audio=context.getSystemService(AudioManager::class.java)
        val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var scenario: ActivityScenario<ProductionPlayerHarnessActivity>?=null
        val subtitleFile=File(context.cacheDir,"exports/p05-${System.nanoTime()}.vtt")
        try {
            main { app.playback=owner;ProductionHarness.owner=owner;ProductionHarness.catalog=catalog }
            scenario=ActivityScenario.launch(Intent(context,ProductionPlayerHarnessActivity::class.java))
            val rejectedBefore=PlaybackService.rejectedStarts.get()
            main { context.startService(Intent(context,PlaybackService::class.java)) }
            waitFor { PlaybackService.rejectedStarts.get()>rejectedBefore }
            assertNull(owner.surfacePlayer.value)
            runBlocking { withContext(Dispatchers.Main) {
                owner.open(SourceHandle(fixtureTitles.first().id,SourceId("p05-late"),"fixture","${server.origin}/neutral.mp4",StoredSourceKind.FILE))
                delay(30);owner.leaveRoute();delay(400)
                assertEquals(0,owner.serviceStartRequests);assertNull(owner.surfacePlayer.value)
            } }
            for((index,path) in listOf("neutral.mp4","ts/master.m3u8","fmp4/master.m3u8","encrypted/index.m3u8").withIndex()) {
                val source=SourceHandle(fixtureTitles.first().id,SourceId("p05-$index"),"fixture","${server.origin}/$path",if(path.endsWith("m3u8")) StoredSourceKind.HLS else StoredSourceKind.FILE)
                runBlocking { withContext(Dispatchers.Main) { assertTrue(owner.open(source) is Outcome.Success) } }
                waitFor(30_000) { check(owner.ui.value.error==null) { "Native case $index failed: ${owner.ui.value.error}" };owner.ui.value.positionMs>1000 && owner.ui.value.phase==PlaybackPhase.PLAYING }
                ui.onNodeWithTag("native-player-surface").assertIsDisplayed()
                var player: ExoPlayer?=null
                main { player=owner.surfacePlayer.value as ExoPlayer }
                waitFor { var video=0;var audioBuffers=0;main { video=player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0;audioBuffers=player!!.audioDecoderCounters?.renderedOutputBufferCount ?: 0 };video>0 && audioBuffers>0 }
                main {
                    assertEquals(MimeTypes.VIDEO_H264,player!!.videoFormat?.sampleMimeType)
                    assertEquals(MimeTypes.AUDIO_AAC,player!!.audioFormat?.sampleMimeType)
                    assertTrue(player!!.currentTracks.groups.any { it.type==C.TRACK_TYPE_VIDEO && it.isSelected })
                    assertTrue(player!!.currentTracks.groups.any { it.type==C.TRACK_TYPE_AUDIO && it.isSelected })
                    assertTrue(owner.processedPcmSamples()>0)
                    owner.pause();owner.seekTo(2000);owner.setSpeed(1.5f);owner.gain(2f)
                    assertEquals(1.5f,player!!.playbackParameters.speed,.001f)
                    owner.lock(true);owner.play();assertFalse(player!!.playWhenReady)
                    owner.lock(false);owner.play()
                }
                if(index==0) {
                    main { owner.pause();owner.seekTo(3000) }
                    waitFor {
                        val retained=personal.snapshot.value.entries[source.titleId]?.personal
                        retained?.positionMs==3000L && retained.durationMs!=null &&
                            context.getSharedPreferences(checkpointName,android.content.Context.MODE_PRIVATE)
                                .getString("identity:${source.titleId.value}",null)==source.id.value
                    }
                    runBlocking { withContext(Dispatchers.Main) { assertTrue(owner.open(source) is Outcome.Success) } }
                    waitFor(15_000) {
                        check(owner.ui.value.error==null) { "Same-source reopen failed: ${owner.ui.value.error}" }
                        owner.ui.value.phase==PlaybackPhase.PLAYING && owner.ui.value.positionMs>=3000
                    }
                    main { assertSame(player,owner.surfacePlayer.value) }
                    android.util.Log.i("EroflixP05","RESUME sameSource=passed persistedPositionMs=3000 singleton=same")
                    val connection=androidx.media3.session.MediaController.Builder(context,
                        androidx.media3.session.SessionToken(context,android.content.ComponentName(context,PlaybackService::class.java))).buildAsync()
                    val controller=connection.get(5,java.util.concurrent.TimeUnit.SECONDS)
                    try {
                        main { controller.pause() }
                        waitFor { owner.ui.value.phase==PlaybackPhase.PAUSED }
                        main { controller.play() }
                        waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
                        main { controller.stop() }
                        waitFor { owner.ui.value.phase==PlaybackPhase.IDLE }
                        main { controller.play() }
                        ui.waitForIdle()
                        main {
                            // ExoPlayer.stop retains playWhenReady; no admitted route/media may resume.
                            assertFalse(owner.canPlay())
                            assertEquals(PlaybackPhase.IDLE,owner.ui.value.phase)
                            assertEquals(0,player!!.mediaItemCount)
                        }
                        waitFor {
                            val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio")
                            val dump=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                            dump.lineSequence().none { "AudioPlaybackConfiguration piid:" in it && "u/pid:${context.applicationInfo.uid}/" in it && "state:started" in it }
                        }
                        android.util.Log.i("EroflixP05","SESSION pause=passed play=passed stop=passed staleResume=denied")
                    } finally { main { controller.release() } }
                    runBlocking { withContext(Dispatchers.Main) { assertTrue(owner.open(source) is Outcome.Success) } }
                    waitFor(15_000) { owner.ui.value.phase==PlaybackPhase.PLAYING }
                    // Genuine surface cancellation must restore temporary speed/brightness.
                    main { owner.setSpeed(1.25f) }
                    ui.onNodeWithTag("native-player-surface").performTouchInput { down(center) }
                    waitFor(4000) { owner.ui.value.speed==2f }
                    ui.onNodeWithTag("native-player-surface").performTouchInput { cancel() }
                    waitFor { owner.ui.value.speed==1.25f }
                    var beforeBrightness=-1f
                    scenario.onActivity { beforeBrightness=it.window.attributes.screenBrightness }
                    ui.onNodeWithTag("native-player-surface").performTouchInput {
                        down(Offset(width*.15f,height*.7f));moveTo(Offset(width*.15f,height*.2f),300)
                    }
                    waitFor { var changed=false;scenario.onActivity { changed=it.window.attributes.screenBrightness!=beforeBrightness };changed }
                    ui.onNodeWithTag("native-player-surface").performTouchInput { cancel() }
                    waitFor { var restored=false;scenario.onActivity { restored=it.window.attributes.screenBrightness==beforeBrightness };restored }
                    main { owner.pause();owner.seekTo(2000) }
                    ui.onNodeWithTag("native-player-surface").performTouchInput {
                        down(0,Offset(width*.4f,height*.5f));down(1,Offset(width*.6f,height*.5f))
                        moveTo(0,Offset(width*.25f,height*.5f));moveTo(1,Offset(width*.75f,height*.5f))
                        up(0);up(1)
                    }
                    ui.onNodeWithTag("native-player-surface").assert(SemanticsMatcher.expectValue(PlayerCropMode,true))
                    main { assertEquals(2000L,player!!.currentPosition) }
                    ui.onNodeWithText("Lock controls").performScrollTo().performClick()
                    ui.onNodeWithTag("native-player-surface").performScrollTo().performTouchInput { down(center) }
                    Thread.sleep(600)
                    ui.onNodeWithTag("native-player-surface").performTouchInput { cancel() }
                    main { assertEquals(1.25f,player!!.playbackParameters.speed,.001f) }
                    ui.onNodeWithText("Unlock controls").assertExists().performScrollTo().performClick()
                    // content URI subtitle survives a previously disabled text preference.
                    subtitleFile.parentFile!!.mkdirs();subtitleFile.writeText("WEBVTT\n\n00:00.000 --> 00:07.900\nNeutral subtitle\n")
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",subtitleFile)
                    main {
                        owner.pause();owner.seekTo(2000)
                        player!!.trackSelectionParameters=player!!.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                        assertTrue(owner.subtitle(uri,MimeTypes.TEXT_VTT) is Outcome.Success)
                    }
                    waitFor { var cue=false;main { cue=player!!.currentCues.cues.any { it.text.toString()=="Neutral subtitle" } };cue }
                    main { assertFalse(C.TRACK_TYPE_TEXT in player!!.trackSelectionParameters.disabledTrackTypes);owner.play() }
                    waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
                    // Default background without PiP cannot be resumed by an external-style play.
                    InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    waitFor { owner.ui.value.phase==PlaybackPhase.PAUSED }
                    main { owner.play();assertFalse(player!!.playWhenReady) }
                    context.startActivity(Intent(context,ProductionPlayerHarnessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    waitFor { var resumed=false;scenario.onActivity { resumed=it.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) };resumed }
                    main { owner.play() }
                    waitFor { owner.ui.value.phase==PlaybackPhase.PLAYING }
                    scenario.onActivity { it.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build()) }
                    waitFor { var pip=false;scenario.onActivity { pip=it.isInPictureInPictureMode };pip }
                    // PiP reports mode before resize/recomposition settles; wait for the actual view.
                    fun pipLayoutReady(): Boolean {
                        var ready=false
                        scenario.onActivity { activity ->
                            val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                            fun players(view: android.view.View): List<androidx.media3.ui.PlayerView> {
                                val own=if(view is androidx.media3.ui.PlayerView && view.isShown && view.isAttachedToWindow) listOf(view) else emptyList()
                                return own + if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { players(view.getChildAt(it)) } else emptyList()
                            }
                            val visible=players(content)
                            if(visible.size==1) {
                                val native=visible.single()
                                val surfaceRect=android.graphics.Rect();val contentRect=android.graphics.Rect()
                                ready=native.player===owner.surfacePlayer.value && native.width==content.width && native.height==content.height &&
                                    native.getGlobalVisibleRect(surfaceRect) && content.getGlobalVisibleRect(contentRect) && surfaceRect==contentRect
                            }
                        }
                        return ready
                    }
                    waitFor(10_000) { pipLayoutReady() }
                    assertTrue(pipLayoutReady())
                    scenario.onActivity { activity ->
                        val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        android.util.Log.i("EroflixP05","PIP nativeSurface=full-content singleton=same measuredWidth=${content.width} measuredHeight=${content.height} chrome=absent")
                    }
                    context.startActivity(Intent(context,ProductionPlayerHarnessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    waitFor { var pip=true;scenario.onActivity { pip=it.isInPictureInPictureMode };!pip }
                    android.util.Log.i("EroflixP05","SURFACE holdCancel=restored brightnessCancel=restored touchUnlock=passed subtitleCue=rendered homeDefault=paused pipReturn=passed")
                }
                val identity=System.identityHashCode(player)
                scenario.recreate()
                waitFor { owner.surfacePlayer.value!=null }
                main { assertEquals(identity,System.identityHashCode(owner.surfacePlayer.value)) }
                // Recreating the visible route cannot admit a notification-style restart after disposal.
                main { owner.leaveRoute();owner.play();assertFalse(player!!.playWhenReady) }
                android.util.Log.i("EroflixP05","NATIVE case=$index frames=advanced tracks=video+audio singleton=reused gainRequest=2.0 pssKiB=${Debug.getPss()}")
            }
            assertTrue(server.observed.isNotEmpty());assertTrue(server.observed.all { it.second })
            assertTrue(personal.snapshot.value.entries.isNotEmpty())
        } finally {
            scenario?.close()
            main { owner.stop();context.stopService(Intent(context,PlaybackService::class.java)) }
            waitFor { owner.surfacePlayer.value==null }
            main { owner.dispose();app.playback=original;ProductionHarness.owner=null;ProductionHarness.catalog=null }
            server.close();root.deleteRecursively();subtitleFile.delete();context.deleteSharedPreferences(checkpointName)
            assertEquals(volume,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }
    @Test fun pcmPipelineEmptyPollingPartialLimitsRepeatedChunksAndOutputAliasing() {
        val processor=GainProcessor()
        val pipeline=androidx.media3.common.audio.AudioProcessingPipeline(com.google.common.collect.ImmutableList.of<AudioProcessor>(processor))
        pipeline.configure(AudioProcessor.AudioFormat(48000,1,C.ENCODING_PCM_16BIT));pipeline.flush()
        // Real sink polls before and between codec chunks, including shared EMPTY_BUFFER.
        repeat(3) { pipeline.queueInput(AudioProcessor.EMPTY_BUFFER);assertFalse(pipeline.output.hasRemaining()) }
        repeat(5) { chunk ->
            val input=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            input.putShort(123);input.putShort((1000+chunk).toShort());input.putShort((-1000-chunk).toShort());input.putShort(456)
            input.position(2);input.limit(6)
            pipeline.queueInput(input)
            assertEquals(6,input.position())
            val output=pipeline.output.order(ByteOrder.nativeOrder())
            assertEquals(4,output.remaining());assertEquals((1000+chunk).toShort(),output.short)
            assertEquals((-1000-chunk).toShort(),output.short)
            assertFalse(pipeline.output.hasRemaining())
        }
        // Defensive repeated-input alias case keeps the old caller buffer consumed.
        val input=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putShort(23).putShort(-23)
        input.flip();processor.queueInput(input)
        val previous=processor.output.order(ByteOrder.nativeOrder())
        processor.queueInput(previous)
        assertFalse(previous.hasRemaining())
        val repeated=processor.output.order(ByteOrder.nativeOrder())
        assertEquals(23.toShort(),repeated.short);assertEquals((-23).toShort(),repeated.short)
        pipeline.reset()
        android.util.Log.i("EroflixP05","PCM pipeline=empty-poll+partial-limit+repeated-chunks+alias passed")
    }
    @Test fun actualPcmGainBuffersPreserveUnityAndBoundAmplification() {
        fun run(gain: Float): ShortArray {
            val processor=GainProcessor();processor.gain=gain
            processor.configure(AudioProcessor.AudioFormat(48000,1,C.ENCODING_PCM_16BIT));processor.flush()
            val source=shortArrayOf(0,1000,-1000,20000,-20000,32767,-32768)
            val input=ByteBuffer.allocateDirect(source.size*2).order(ByteOrder.nativeOrder())
            source.forEach(input::putShort);input.flip();processor.queueInput(input)
            val output=processor.output.order(ByteOrder.nativeOrder())
            return ShortArray(output.remaining()/2) { output.short }.also { processor.reset() }
        }
        val one=run(1f);val two=run(2f)
        assertArrayEquals(shortArrayOf(0,1000,-1000,20000,-20000,32767,-32768),one)
        assertTrue(two[1]>one[1]);assertTrue(two[2]<one[2]);assertTrue(two[5]<32767);assertTrue(two[6]>-32768)
        android.util.Log.i("EroflixP05","PCM unity=byte-exact gain2=amplifies-low-level limiting=bounded samples=7")
    }
}
