package tech.imitravanu.eroflix.core.media

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.*

interface PlaybackApplication { val playbackOrNull: ProductionPlayback? }
enum class PlaybackPhase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, WEB, FAILED }
data class PlaybackUi(val phase: PlaybackPhase=PlaybackPhase.IDLE, val title: TitleSummary?=null,
    val source: SourceHandle?=null, val positionMs: Long=0, val durationMs: Long?=null,
    val bufferedMs: Long=0, val speed: Float=1f, val gain: Float=1f,
    val web: Resolution.WebEmbed?=null, val error: AppError?=null, val message: String?=null,
    val locked: Boolean=false, val backgroundAudio: Boolean=false, val inPip: Boolean=false)

/** Service owns the sole ExoPlayer; UI only borrows its surface/track adapter. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ProductionPlayback(private val context: Context, private val catalog: CatalogRepository,
    private val personal: PersonalRepository, private val resolver: SourceResolver, private val fixtureLoopback: Boolean=false, private val checkpointName: String="playback-timeline-v1", private val nativeDataSource: ((Resolution.NativeMedia)->androidx.media3.datasource.DataSource.Factory)?=null,
    private val webPolicyOverride: ((Resolution.WebEmbed)->tech.imitravanu.eroflix.core.network.WebSourcePolicy?)?=null,
    val webResourceOverride: ((String)->android.webkit.WebResourceResponse?)?=null) : PlayerControl {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(PlaybackUi())
    val ui=mutable.asStateFlow()
    private val playerMutable=MutableStateFlow<Player?>(null)
    val surfacePlayer=playerMutable.asStateFlow()
    private val legacy=MutableStateFlow(PlayerState())
    override val state=legacy.asStateFlow()
    private var engine: ExoPlayer?=null
    private var serviceReady=CompletableDeferred<Unit>()
    private var resolveJob: Job?=null
    private var resolution: Resolution.NativeMedia?=null
    var serviceStartRequests=0
        private set
    private var timeline: String?=null
    private val checkpoints=context.getSharedPreferences(checkpointName,Context.MODE_PRIVATE)
    private val qualityMutable=MutableStateFlow(checkpoints.getInt("preferredMaxHeight",1080).takeIf { it in listOf(480,720,1080,2160) } ?: 1080)
    val preferredMaxHeight=qualityMutable.asStateFlow()
    private val defaultBackgroundMutable=MutableStateFlow(checkpoints.getBoolean("defaultBackgroundAudio",false))
    val defaultBackgroundAudio=defaultBackgroundMutable.asStateFlow()
    fun setDefaultBackgroundAudio(enabled: Boolean) {
        checkpoints.edit().putBoolean("defaultBackgroundAudio",enabled).apply()
        defaultBackgroundMutable.value=enabled
        backgroundAudio(enabled)
    }
    fun setPreferredMaxHeight(height: Int) {
        require(height in listOf(480,720,1080,2160))
        qualityMutable.value=height
        checkpoints.edit().putInt("preferredMaxHeight",height).apply()
        engine?.let { it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setMaxVideoSize(height*16/9,height).build() }
    }
    private var oldIdentity: String?=null
    private var resumeSameSource=false
    private var pendingResume=0L
    private var oldDuration: Long?=null
    private val admission=PlaybackAdmission()
    private var established=false
    private var restoredTracks=false
    private var progressJob: Job?=null
    private val intervals=AdvancingIntervals()
    private var discontinuity=true
    private var lastCheckpoint=0L
    private var intervalStart: Long?=null
    private sealed interface ProgressEvent {
        data class Observation(val value: PlaybackObservation): ProgressEvent
        data class NewCycle(val title: TitleSummary,val result: CompletableDeferred<Outcome<Unit>>): ProgressEvent
    }
    private val observationQueue=kotlinx.coroutines.channels.Channel<ProgressEvent>(64)
    private val observationMutable=MutableSharedFlow<PlaybackObservation>(extraBufferCapacity=16)
    val observations=observationMutable.asSharedFlow()
    init {
        admission.backgroundAudio=defaultBackgroundMutable.value
        mutable.update { it.copy(backgroundAudio=defaultBackgroundMutable.value) }
        scope.launch { mutable.collect { value -> legacy.value=PlayerState(value.title?.id,value.positionMs,value.durationMs,value.phase==PlaybackPhase.PLAYING,value.error) } }
        scope.launch(Dispatchers.IO) {
            for(event in observationQueue) {
                if(event is ProgressEvent.NewCycle) { event.result.complete(personal.startWatchCycle(event.title));continue }
                val observation=(event as ProgressEvent.Observation).value
                when(personal.recordProgress(observation)) {
                    is Outcome.Success -> {
                        checkpoints.edit().putString("identity:${observation.title.id.value}",observation.timelineId)
                            .putString("source:${observation.title.id.value}",observation.sourceId.value).apply()
                        observationMutable.emit(observation)
                    }
                    is Outcome.Failure -> mutable.update { it.copy(message="Progress could not be saved. Your previous checkpoint is preserved.") }
                }
            }
        }
    }
    private var gainProcessor: GainProcessor?=null
    @Volatile private var sinkTrack: android.media.AudioTrack?=null
    internal fun observeSinkTrack(track: android.media.AudioTrack) { sinkTrack=track }
    /** No route preference is set; reports only the currently playing owned sink's device type. */
    fun observedAudioRouteType(): Int? = runCatching {
        sinkTrack?.takeIf { it.playState==android.media.AudioTrack.PLAYSTATE_PLAYING }?.routedDevice?.type
    }.getOrNull()
    var closeWeb: (() -> Unit)?=null
    var onLockUi: (() -> Unit)?=null
    private val listener=object: Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if(mutable.value.locked && player.playWhenReady) player.pause()
            publish()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
            intervalStart?.let { checkpoint(false,it,oldPosition.positionMs) }
            intervalStart=null;intervals.reset();discontinuity=true;checkpoint(true)
        }
        override fun onTracksChanged(tracks: Tracks) {
            if(restoredTracks) return
            if(tracks.groups.isEmpty()) return
            restoredTracks=true
            val source=ui.value.source ?: return
            val p=engine ?: return
            var builder=p.trackSelectionParameters.buildUpon()
            for(type in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT)) {
                val id=checkpoints.getString("track:${source.id.value}:$type",null) ?: continue
                val match=tracks.groups.firstNotNullOfOrNull { group ->
                    if(group.type!=type) null else (0 until group.length).firstOrNull { group.isTrackSupported(it) && group.getTrackFormat(it).id==id }?.let { group to it }
                }
                match?.let { builder=builder.setOverrideForType(TrackSelectionOverride(it.first.mediaTrackGroup,it.second)) }
            }
            p.trackSelectionParameters=builder.build()
        }
        override fun onPlayerError(error: PlaybackException) {
            val decoding=error.errorCode in 3000..4999
            mutable.update { it.copy(phase=PlaybackPhase.FAILED,error=AppError(if(decoding) ErrorCode.UNSUPPORTED else ErrorCode.NETWORK,
                if(decoding) "Media or subtitle parsing/decoding failed (${error.errorCodeName}). Retry the source to remove the external subtitle, or choose another source."
                else "Playback stopped: ${error.errorCodeName}. Retry this source or choose another.",true)) }
            checkpoint(true)
        }
    }
    override suspend fun open(source: SourceHandle,startPositionMs: Long): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        if(admission.locked) return@withContext Outcome.Failure(AppError(ErrorCode.AUTH_REQUIRED,"Unlock the app before playback."))
        checkpoint(engine?.isPlaying!=true,if(engine?.isPlaying==true) intervalStart else null)
        intervalStart=null
        closeWeb?.invoke(); engine?.pause(); resolveJob?.cancel()
        val token=admission.begin() ?: return@withContext Outcome.Failure(AppError(ErrorCode.CANCELED,"Open playback from the visible app."))
        established=false
        gainProcessor?.gain=1f
        restoredTracks=false
        engine?.stop();engine?.clearMediaItems()
        resolution=null;timeline=null;pendingResume=0;intervalStart=null;intervals.reset();discontinuity=true
        val details=withContext(Dispatchers.IO) { catalog.details(source.titleId) }
        if(!admission.valid(token)) return@withContext Outcome.Failure(AppError(ErrorCode.CANCELED,"Playback selection was canceled."))
        val title=(details as? Outcome.Success)?.value?.summary ?: return@withContext Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Title metadata is unavailable."))
        mutable.value=PlaybackUi(phase=PlaybackPhase.RESOLVING,title=title,source=source,backgroundAudio=mutable.value.backgroundAudio,locked=admission.locked)
        // Accept explicit selection immediately so resolving/errors have visible player UI.
        resolveJob=scope.launch {
            val result=withContext(Dispatchers.IO) { resolver.resolve(source,ResolvePurpose.PLAYBACK) }
            if(!admission.valid(token)) return@launch
            when(result) {
                is Resolution.Unavailable -> mutable.update { it.copy(phase=PlaybackPhase.FAILED,error=result.error) }
                is Resolution.WebEmbed -> {
                    engine?.stop(); engine?.clearMediaItems()
                    mutable.update { it.copy(phase=PlaybackPhase.WEB,web=result,message=result.unavailableExplanation) }
                }
                is Resolution.NativeMedia -> {
                    try {
                        if(result.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
                            mutable.update { it.copy(phase=PlaybackPhase.FAILED,error=AppError(ErrorCode.EXPIRED,"This source expired. Retry to resolve it again.",true)) };return@launch
                        }
                        serviceStartRequests++
                        context.startService(Intent(context,PlaybackService::class.java))
                        withTimeout(10_000) { serviceReady.await() }
                        if(!admission.valid(token)) return@launch
                        val player=engine ?: return@launch
                        resolution=result
                        val retained=withContext(Dispatchers.IO) { personal.observeState(source.titleId).first() }
                        if(!admission.valid(token)) return@launch
                        pendingResume=if(startPositionMs>0) startPositionMs else retained.positionMs
                        oldDuration=retained.durationMs
                        val key=source.titleId.value
                        oldIdentity=checkpoints.getString("identity:$key",null)
                        resumeSameSource=checkpoints.getString("source:$key",null)==source.id.value
                        timeline=result.identityFingerprint
                        val item=MediaItem.Builder().setMediaId(source.id.value).setUri(result.uri).setMimeType(result.mimeType)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.title).build()).build()
                        val data=androidx.media3.datasource.DefaultDataSource.Factory(context,networkSource(result))
                        player.setMediaSource(DefaultMediaSourceFactory(data).createMediaSource(item))
                        mutable.update { it.copy(phase=PlaybackPhase.BUFFERING) }
                        player.setPlaybackSpeed(checkpoints.getFloat("speed",1f).coerceIn(.25f,3f))
                        player.prepare(); player.playWhenReady=true
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { mutable.update { it.copy(phase=PlaybackPhase.FAILED,error=AppError(ErrorCode.NETWORK,"The player could not prepare this source. Retry.",true)) } }
                }
            }
        }
        Outcome.Success(Unit)
    }
    internal fun attach(player: ExoPlayer, processor: GainProcessor) {
        check(engine==null) { "Only one production player may exist" }
        engine=player; gainProcessor=processor; playerMutable.value=player
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setMaxVideoSize(qualityMutable.value*16/9,qualityMutable.value).build()
        player.addListener(listener); serviceReady.complete(Unit)
        progressJob=scope.launch {
            while(isActive) {
                publish()
                val p=engine
                if(p!=null) {
                    val now=SystemClock.elapsedRealtime()
                    val from=intervals.sample(now,p.currentPosition,p.isPlaying,p.playbackParameters.speed,discontinuity)
                    val wasDiscontinuous=discontinuity;discontinuity=false
                    if(from!=null && intervalStart==null) intervalStart=from
                    if(p.isPlaying && established && now-lastCheckpoint>=5000) {
                        checkpoint(wasDiscontinuous,if(wasDiscontinuous) null else intervalStart)
                        intervalStart=null;lastCheckpoint=now
                    }
                }
                delay(1000)
            }
        }
    }
    internal fun detach() {
        checkpoint(true); progressJob?.cancel(); engine?.removeListener(listener)
        engine=null; playerMutable.value=null; gainProcessor=null;sinkTrack=null
        serviceReady=CompletableDeferred(); mutable.update { it.copy(phase=PlaybackPhase.IDLE) }
    }
    private fun publish() {
        val p=engine ?: return
        if(mutable.value.phase in listOf(PlaybackPhase.WEB,PlaybackPhase.RESOLVING,PlaybackPhase.FAILED,PlaybackPhase.IDLE)) return
        val duration=p.duration.takeIf { it>0 && it!=C.TIME_UNSET && !p.isCurrentMediaItemLive }
        if(p.playbackState==Player.STATE_READY && pendingResume>0) {
            val resume=pendingResume
            pendingResume=0 // seek callbacks can synchronously reenter publish().
            if(resumeSameSource && compatibleTimeline(oldIdentity,timeline,oldDuration,duration) && resume<duration!!) {
                p.seekTo(resume)
            } else mutable.update { it.copy(message="Resume position was not reused because exact source identity and a compatible finite duration were not established.") }
            discontinuity=true
        }
        if(p.playbackState==Player.STATE_READY && pendingResume==0L) established=true
        val phase=when { p.playbackState==Player.STATE_ENDED -> PlaybackPhase.ENDED; p.playbackState==Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING; p.isPlaying -> PlaybackPhase.PLAYING; else -> PlaybackPhase.PAUSED }
        mutable.update { it.copy(phase=phase,positionMs=p.currentPosition.coerceAtLeast(0),durationMs=duration,bufferedMs=p.bufferedPosition,speed=p.playbackParameters.speed) }
        legacy.value=PlayerState(mutable.value.title?.id,p.currentPosition.coerceAtLeast(0),duration,p.isPlaying)
    }
    private fun checkpoint(discontinuous: Boolean, from: Long?=null, positionOverride: Long?=null) {
        val value=mutable.value; val title=value.title ?: return; val source=value.source ?: return
        val p=engine ?: return; val identity=timeline ?: return
        if(!established || pendingResume>0 || p.playbackState!=Player.STATE_READY && p.playbackState!=Player.STATE_ENDED) return
        if(value.web!=null || value.phase in listOf(PlaybackPhase.IDLE,PlaybackPhase.RESOLVING,PlaybackPhase.WEB)) return
        val observation=PlaybackObservation(title,source.id,identity,(positionOverride ?: p.currentPosition).coerceAtLeast(0),
            p.duration.takeIf { it>0 && it!=C.TIME_UNSET && !p.isCurrentMediaItemLive },System.currentTimeMillis(),from,discontinuous,p.playbackParameters.speed)
        if(!observationQueue.trySend(ProgressEvent.Observation(observation)).isSuccess) mutable.update { it.copy(message="Progress could not be queued. Playback continues; pause and retry later.") }
    }
    suspend fun restoreRoute(titleId: TitleId, sourceId: SourceId) = withContext(Dispatchers.Main.immediate) {
        admission.restoreRoute()
        if(ui.value.phase!=PlaybackPhase.IDLE) return@withContext
        val details=withContext(Dispatchers.IO) { catalog.details(titleId) }
        if(!admission.route || ui.value.phase!=PlaybackPhase.IDLE) return@withContext
        val title=(details as? Outcome.Success)?.value
        if(title==null) {
            mutable.value=PlaybackUi(phase=PlaybackPhase.FAILED,error=(details as? Outcome.Failure)?.error ?: AppError(ErrorCode.NOT_FOUND,"This title is no longer available."),locked=admission.locked)
            return@withContext
        }
        val selected=title.sources.firstOrNull { it.id==sourceId }
        mutable.value=PlaybackUi(phase=if(selected==null) PlaybackPhase.FAILED else PlaybackPhase.PAUSED,title=title.summary,source=selected,
            error=if(selected==null) AppError(ErrorCode.NOT_FOUND,"The selected source is no longer available in this title snapshot. Choose a source; nothing was substituted.") else null,
            message=if(selected!=null) "Playback was not restored automatically. Retry the selected source to continue." else null,locked=admission.locked)
    }
    fun selectTrack(group: Tracks.Group,index: Int) {
        if(index !in 0 until group.length || !group.isTrackSupported(index)) return
        val p=engine ?: return
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(group.type,false)
            .clearVideoSizeConstraints().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index)).build()
        ui.value.source?.let { source -> group.getTrackFormat(index).id?.let { id ->
            checkpoints.edit().putString("track:${source.id.value}:${group.type}",id).apply()
        } }
    }
    suspend fun openCached(title: TitleSummary,source: SourceHandle,item: MediaItem,factory: androidx.media3.datasource.DataSource.Factory,identity: String): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        if(admission.locked) return@withContext Outcome.Failure(AppError(ErrorCode.AUTH_REQUIRED,"Unlock before offline playback."))
        checkpoint(engine?.isPlaying!=true,if(engine?.isPlaying==true) intervalStart else null)
        closeWeb?.invoke();resolveJob?.cancel();engine?.stop();engine?.clearMediaItems()
        val token=admission.begin() ?: return@withContext Outcome.Failure(AppError(ErrorCode.CANCELED,"Open offline playback from the visible app."))
        established=false;resolution=null;pendingResume=0;timeline=identity;intervalStart=null;intervals.reset();discontinuity=true
        mutable.value=PlaybackUi(phase=PlaybackPhase.RESOLVING,title=title,source=source,locked=admission.locked,message="Cache-only playback. Missing cached bytes require download repair; no network fallback.")
        resolveJob=scope.launch {
            try {
                context.startService(Intent(context,PlaybackService::class.java))
                withTimeout(10_000) { serviceReady.await() }
                if(!admission.valid(token)) return@launch
                val p=engine ?: return@launch
                mutable.update { it.copy(phase=PlaybackPhase.BUFFERING) }
                p.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item));p.prepare();p.playWhenReady=true
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.update { it.copy(phase=PlaybackPhase.FAILED,error=AppError(ErrorCode.LOCAL_ONLY,"Offline media could not be opened. Download repair is required.",true)) } }
        }
        Outcome.Success(Unit)
    }
    fun webPolicy(media: Resolution.WebEmbed)=webPolicyOverride?.invoke(media) ?: tech.imitravanu.eroflix.core.network.WebSourcePolicy.forEntry(media.entryUri)
    private fun networkSource(media: Resolution.NativeMedia)=nativeDataSource?.invoke(media) ?: scopedMediaDataSource(media,fixtureLoopback=fixtureLoopback)
    fun subtitle(uri: android.net.Uri, mime: String): Outcome<Unit> {
        if(uri.scheme!="content") return Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Choose a subtitle using Documents."))
        val p=engine ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"No native player is active."))
        val media=resolution ?: return Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"External subtitles require native playback."))
        val item=p.currentMediaItem ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"No native title is active."))
        val config=MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val position=p.currentPosition; val playing=p.playWhenReady
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        p.setMediaSource(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context,networkSource(media))).createMediaSource(item.buildUpon().setSubtitleConfigurations(listOf(config)).build()),position)
        p.prepare();p.playWhenReady=playing;discontinuity=true
        return Outcome.Success(Unit)
    }
    fun dispose() { stop();scope.cancel();observationQueue.close() }
    fun serviceAllowed()=admission.route && admission.foreground && !admission.locked && ui.value.phase==PlaybackPhase.RESOLVING
    fun canPip(): Boolean {
        val p=engine ?: return false
        return admission.route && !admission.locked && established && ui.value.web==null && p.videoSize.width>0 && p.videoSize.height>0 &&
            ui.value.phase in listOf(PlaybackPhase.PLAYING,PlaybackPhase.PAUSED,PlaybackPhase.BUFFERING)
    }
    fun canPlay()=!admission.locked && admission.route && (admission.foreground || admission.pip || admission.backgroundAudio)
    fun play() { if(canPlay()) { engine?.play(); } }
    override fun pause() {
        val wasPlaying=engine?.isPlaying==true
        engine?.pause()
        checkpoint(!wasPlaying,if(wasPlaying) intervalStart else null)
        intervalStart=null;discontinuity=true
    }
    override fun seekTo(positionMs: Long) {
        intervalStart?.let { checkpoint(false,it) }
        intervalStart=null;intervals.reset();discontinuity=true
        engine?.seekTo(positionMs.coerceAtLeast(0))
    }
    suspend fun replayFromStart(): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val title=ui.value.title ?: return@withContext Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"No title is playing."))
        val player=engine ?: return@withContext Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"Replay requires a native player."))
        val wasPlaying=player.isPlaying
        pause()
        val result=CompletableDeferred<Outcome<Unit>>()
        observationQueue.send(ProgressEvent.NewCycle(title,result))
        val outcome=result.await()
        if(outcome is Outcome.Success) {
            if(engine!==player || ui.value.title?.id!=title.id || !canPlay()) return@withContext Outcome.Failure(AppError(ErrorCode.CANCELED,"Player changed before replay could start."))
            seekTo(0); if(wasPlaying) player.play()
        }
        outcome
    }
    private fun changeSpeed(multiplier: Float,persist: Boolean) {
        val p=engine ?: return
        val next=multiplier.coerceIn(.25f,3f)
        if(p.playbackParameters.speed!=next) {
            intervalStart?.let { checkpoint(false,it) } // Snapshot uses the old speed before mutation.
            intervalStart=null;intervals.reset()
            p.setPlaybackSpeed(next)
            intervals.sample(SystemClock.elapsedRealtime(),p.currentPosition,p.isPlaying,next,false)
        }
        if(persist) checkpoints.edit().putFloat("speed",next).apply()
        publish()
    }
    fun previewSpeed(multiplier: Float)=changeSpeed(multiplier,false)
    override fun setSpeed(multiplier: Float)=changeSpeed(multiplier,true)
    fun processedPcmSamples()=gainProcessor?.processedSamples ?: 0L
    fun gain(value: Float) { gainProcessor?.gain=value;mutable.update { it.copy(gain=value.coerceIn(1f,2f)) } }
    fun backgroundAudio(enabled: Boolean) { admission.backgroundAudio=enabled;mutable.update { it.copy(backgroundAudio=enabled) } }
    fun visibility(visible: Boolean, pip: Boolean) {
        admission.visible(visible,pip)
        mutable.update { it.copy(inPip=pip) }
        if(!visible && !pip) {
            resolveJob?.cancel()
            if(ui.value.phase==PlaybackPhase.RESOLVING) mutable.update { it.copy(phase=PlaybackPhase.PAUSED,message="Pending playback canceled on background. Retry from the visible player.") }
            closeWeb?.invoke()
            if(ui.value.web!=null) mutable.update { it.copy(phase=PlaybackPhase.PAUSED,web=null,message="Web playback closed in the background. Retry to reopen.") }
            if(!ui.value.backgroundAudio || ui.value.locked) pause()
        }
    }
    fun leaveRoute() { admission.leave();resolveJob?.cancel();closeWeb?.invoke();pause();mutable.update { it.copy(web=null) } }
    fun routeVisible() { /* Explicit open reserves the route; restoration never starts playback. */ }
    fun lock(locked: Boolean) {
        admission.lock(locked)
        if(locked) resolveJob?.cancel()
        mutable.update { it.copy(locked=locked,web=if(locked) null else it.web) }
        if(locked) { onLockUi?.invoke();closeWeb?.invoke();pause();engine?.currentMediaItem?.let { item -> engine?.replaceMediaItem(0,item.buildUpon().setMediaMetadata(MediaMetadata.EMPTY).build()) } }
    }
    suspend fun retry() { ui.value.source?.let { open(it,ui.value.positionMs) } }
    override fun stop() {
        checkpoint(engine?.isPlaying!=true,if(engine?.isPlaying==true) intervalStart else null)
        intervalStart=null
        admission.stop();established=false;resolveJob?.cancel();closeWeb?.invoke();engine?.stop();engine?.clearMediaItems();intervals.reset()
        mutable.value=PlaybackUi(backgroundAudio=ui.value.backgroundAudio,locked=admission.locked);legacy.value=PlayerState()
    }
}

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {
    companion object { val rejectedStarts=java.util.concurrent.atomic.AtomicInteger() }
    private var session: MediaSession?=null
    private var owner: ProductionPlayback?=null
    override fun onCreate() {
        super.onCreate()
        val playback=(application as? PlaybackApplication)?.playbackOrNull
        if(playback==null || !playback.serviceAllowed()) { rejectedStarts.incrementAndGet();stopSelf();return }
        owner=playback
        val gain=GainProcessor()
        val renderers=object: DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context,enableFloatOutput: Boolean,enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(gain)).setEnableFloatOutput(false)
                    .setAudioTrackProvider(object: DefaultAudioSink.AudioTrackProvider {
                        override fun getAudioTrack(config: AudioSink.AudioTrackConfig,attributes: AudioAttributes,sessionId: Int,trackContext: Context?): android.media.AudioTrack =
                            DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,sessionId,trackContext).also(playback::observeSinkTrack)
                    }).build()
        }
        val player=ExoPlayer.Builder(this,renderers).setTrackSelector(androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            parameters=buildUponParameters().setMaxVideoSize(1920,1080).setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
        }).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        player.setHandleAudioBecomingNoisy(true)
        val sessionPlayer=object: ForwardingPlayer(player) {
            override fun play() { if(playback.canPlay()) super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if(!playWhenReady || playback.canPlay()) super.setPlayWhenReady(playWhenReady) }
            override fun stop() { playback.stop() }
        }
        session=MediaSession.Builder(this,sessionPlayer).build()
        playback.attach(player,gain)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo)=session
    override fun onTaskRemoved(rootIntent: Intent?) { owner?.stop();stopSelf() }
    override fun onDestroy() { owner?.detach();session?.run { player.release();release() };session=null;super.onDestroy() }
}
