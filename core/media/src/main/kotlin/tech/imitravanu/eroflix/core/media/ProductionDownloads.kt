package tech.imitravanu.eroflix.core.media

import android.content.*
import android.net.*
import android.os.*
import androidx.media3.common.*
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.imitravanu.eroflix.model.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadOffer(val token: Long,val intent: DownloadIntent,val options: List<DownloadTrackRef>,val progressive: Boolean)
data class CompletedDownloadLease(val record: PersistedDownload,val mediaItem: MediaItem,
    val dataSourceFactory: DataSource.Factory,val bytes: Long,val manifestUris: List<String>,val release: () -> Unit)
interface DownloadApplication { val downloadOwner: StateFlow<ProductionDownloads?> }

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ProductionDownloads private constructor(private val context: Context,private val store: DownloadIntentStore,
    private val resolver: SourceResolver,private val playback: ProductionPlayback,
    val cache: SimpleCache,private val database: StandaloneDatabaseProvider,
    private val executor: ExecutorService,initialRecords: List<PersistedDownload>,private val fixtureLoopback: Boolean=false,
    private val settingsName: String="download-settings-v1",private val networkOverride: (() -> Boolean)?=null) : DownloadController {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val exportLeases=mutableMapOf<String,Int>()
    private val stagingReservations=ConcurrentHashMap<String,Long>()
    private val records=ConcurrentHashMap<String,PersistedDownload>().apply { initialRecords.forEach { put(it.id.value,it) } }
    private val transferred=ConcurrentHashMap<String,Long>()
    private val snapshots=ConcurrentHashMap<String,Download>()
    private val mutable=MutableStateFlow<List<DownloadJob>>(emptyList())
    override val jobs=mutable.asStateFlow()
    private val offerMutable=MutableStateFlow<DownloadOffer?>(null)
    val offer=offerMutable.asStateFlow()
    private val preparingMutable=MutableStateFlow(false)
    val preparing=preparingMutable.asStateFlow()
    private val messageMutable=MutableStateFlow<String?>(null)
    val message=messageMutable.asStateFlow()
    private val settings=context.getSharedPreferences(settingsName,Context.MODE_PRIVATE)
    private val wifiMutable=MutableStateFlow(settings.getBoolean("wifiOnly",true))
    val wifiOnly=wifiMutable.asStateFlow()
    private val parallelMutable=MutableStateFlow(settings.getInt("parallel",2).coerceIn(1,4))
    val parallel=parallelMutable.asStateFlow()
    private var pausedAll=settings.getBoolean("pauseAll",false)
    private var helper: DownloadHelper?=null
    private var preparedNative: Resolution.NativeMedia?=null
    private var sequence=0L
    private var nextOrder=0L
    @Volatile private var foregroundActive=false
    val isForegroundActive get()=foregroundActive
    private var refreshing=false
    private var policyDirty=false
    private val persistence=Mutex()
    private val epochs=mutableMapOf<String,Long>()
    private var preparationJob: Job?=null
    private fun invalidate(id: String) { epochs[id]=(epochs[id] ?: 0L)+1 }
    private val pendingRemoval=mutableMapOf<String,CompletableDeferred<Unit>>()
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val callback=object: ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { scope.launch { updatePolicy() } }
        override fun onLost(network: Network) { scope.launch { updatePolicy() } }
    }
    val manager: DownloadManager
    init {
        val factory=DownloaderFactory { request ->
            val record=records[request.id]
            if(record==null) object: Downloader {
                override fun download(progressListener: Downloader.ProgressListener?) { throw IOException("No durable intent; orphan index request is blocked") }
                override fun cancel()=Unit
                override fun remove() { cache.keys.filter { it.startsWith("${request.id}|") }.forEach { cache.removeResource(it) } }
            } else DefaultDownloaderFactory(cacheFactory(record,false),executor).createDownloader(request)
        }
        manager=DownloadManager(context,DefaultDownloadIndex(database),factory)
        manager.pauseDownloads();manager.maxParallelDownloads=parallel.value;manager.minRetryCount=2
        manager.requirements=Requirements(Requirements.NETWORK)
        manager.addListener(object: DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) { scope.launch { rebuild();updatePolicy() } }
            override fun onDownloadChanged(downloadManager: DownloadManager,download: Download,finalException: Exception?) {
                snapshots[download.request.id]=download;transferred[download.request.id]=download.bytesDownloaded
                if(finalException!=null) messageMutable.value="Download failed. Resume rechecks source identity; incompatible partial media is restarted."
                publish()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager,download: Download) {
                snapshots.remove(download.request.id);transferred.remove(download.request.id)
                pendingRemoval.remove(download.request.id)?.complete(Unit)
                scope.launch {
                    if(records[download.request.id]?.desired==DownloadDesired.REMOVE) {
                        when(store.deleteDownload(JobId(download.request.id))) { is Outcome.Success -> records.remove(download.request.id);is Outcome.Failure -> messageMutable.value="Removal metadata could not be saved. Retry removal." }
                    }
                    publish()
                }
            }
        })
        connectivity.registerDefaultNetworkCallback(callback)
        scope.launch { while(isActive) { publish();delay(1000) } }
        scope.launch { playback.ui.map { it.phase==PlaybackPhase.PLAYING }.distinctUntilChanged().collect { active -> manager.maxParallelDownloads=if(active) 1 else parallel.value } }
    }
    companion object {
        const val STOP_USER=1
        const val STOP_SYSTEM=2
        const val STOP_NETWORK=3
        const val STOP_ALL=4
        suspend fun create(context: Context,store: DownloadIntentStore,resolver: SourceResolver,playback: ProductionPlayback,
            directory: File=File(context.filesDir,"downloads"),settingsName: String="download-settings-v1",fixtureLoopback: Boolean=false,networkOverride: (() -> Boolean)?=null): ProductionDownloads {
            val database=StandaloneDatabaseProvider(object: ContextWrapper(context) {
                override fun getDatabasePath(name: String)=File(directory,"index/$name").also { it.parentFile?.mkdirs() }
                override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?)=
                    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
                override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,errorHandler: android.database.DatabaseErrorHandler?)=
                    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
                override fun deleteDatabase(name: String)=android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            })
            val cache=withContext(Dispatchers.IO) {
                val root=File(directory,"cache");root.mkdirs();File(directory,".nomedia").createNewFile()
                SimpleCache(root,NoOpCacheEvictor(),database)
            }
            val initial=when(val result=withContext(Dispatchers.IO) { store.downloads() }) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> { cache.release();database.close();throw IllegalStateException(result.error.message) }
            }
            return withContext(Dispatchers.Main.immediate) {
                ProductionDownloads(context.applicationContext,store,resolver,playback,cache,database,Executors.newFixedThreadPool(4),initial,fixtureLoopback,settingsName,networkOverride).also { it.initialize() }
            }
        }
    }
    private suspend fun initialize() {
        when(val result=withContext(Dispatchers.IO) { store.downloads() }) {
            is Outcome.Failure -> { messageMutable.value=result.error.message;return }
            is Outcome.Success -> result.value.forEach { records[it.id.value]=it;nextOrder=maxOf(nextOrder,it.order+1) }
        }
        rebuild();publish()
        if(records.values.any { it.desired==DownloadDesired.RUN }) requestForeground()
    }
    private suspend fun rebuild() {
        val all=withContext(Dispatchers.IO) {
            manager.downloadIndex.getDownloads().use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.download) } }
        }
        all.forEach { snapshots[it.request.id]=it;transferred[it.request.id]=it.bytesDownloaded;if(!records.containsKey(it.request.id)) manager.removeDownload(it.request.id) }
        records.values.filter { it.desired==DownloadDesired.REMOVE }.forEach { record ->
            if(all.none { it.request.id==record.id.value }) {
                withContext(Dispatchers.IO) { store.deleteDownload(record.id) };records.remove(record.id.value)
            } else manager.removeDownload(record.id.value)
        }
    }
    private fun networkAllowed(): Boolean {
        networkOverride?.let { return it() }
        val network=connectivity.activeNetwork ?: return false
        val caps=connectivity.getNetworkCapabilities(network) ?: return false
        val connected=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        // VPN capabilities can expose their underlying Wi-Fi transport. A VPN-only/unknown
        // transport fails closed; unrelated connected Wi-Fi never authorizes cellular traffic.
        return downloadNetworkAllowed(connected,wifi,wifiOnly.value)
    }
    private fun remaining(record: PersistedDownload)=((record.estimatedBytes ?: UNKNOWN_DOWNLOAD_RESERVATION)-(transferred[record.id.value] ?: 0)).coerceAtLeast(if(record.estimatedBytes==null) UNKNOWN_DOWNLOAD_RESERVATION else 0)
    private fun reservations(except: String?)=records.values.filter { it.id.value!=except && it.desired!=DownloadDesired.REMOVE && snapshots[it.id.value]?.state!=Download.STATE_COMPLETED }.map(::remaining)+stagingReservations.values
    private fun ensureWriteAllowed(id: String) {
        if(!foregroundActive || pausedAll) throw IOException("Download service is not active")
        if(!networkAllowed()) throw IOException("Download network policy is waiting")
        if(!hasDownloadSpace(context.filesDir.usableSpace,reservations(id),1024*1024)) throw IOException("Storage reserve reached; free space and resume")
        val record=records[id] ?: throw IOException("Missing durable download intent")
        if(record.estimatedBytes==null && (transferred[id] ?: 0)>8L*1024*1024*1024) throw IOException("Unknown-length download exceeded 8 GiB policy; verify size before retry")
    }
    private fun cacheFactory(record: PersistedDownload,offline: Boolean): CacheDataSource.Factory {
        val result=CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { spec -> "${record.id.value}|${spec.key ?: spec.uri}" }
        if(offline) return result.setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        val upstream=scopedMediaDataSource(record.native,fixtureLoopback=fixtureLoopback)
        return result.setUpstreamDataSourceFactory(DataSource.Factory {
            val delegate=upstream.createDataSource()
            object: DataSource by delegate {
                private var sinceCheck=0L
                override fun open(dataSpec: DataSpec): Long {
                    ensureWriteAllowed(record.id.value)
                    val length=delegate.open(dataSpec)
                    try { validateResource(record,dataSpec,delegate.responseHeaders) }
                    catch(e: IOException) { delegate.close();throw e }
                    return length
                }
                override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(sinceCheck>=1024*1024) { ensureWriteAllowed(record.id.value);sinceCheck=0 }
                    return delegate.read(buffer,offset,length).also { if(it>0) sinceCheck+=it }
                }
            }
        })
    }
    private fun validateResource(record: PersistedDownload,spec: DataSpec,headers: Map<String,List<String>>) {
        fun header(name: String)=headers.entries.firstOrNull { it.key.equals(name,true) }?.value?.firstOrNull()
        val key="${record.id.value}|${spec.key ?: spec.uri}"
        val old=cache.getContentMetadata(key)
        val cached=cache.getCachedSpans(key).isNotEmpty()
        val etag=header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/",true) }
        val total=header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: header("Content-Length")?.toLongOrNull()?.takeIf { spec.position==0L }
        if(cached && (etag==null || total==null || old.get("p06-etag","")!=etag || old.get("p06-length",-1L)!=total)) {
            throw IOException("Cached resource identity cannot be validated. Resume restarts incompatible partial media.")
        }
        if(!cached && etag!=null && total!=null) cache.applyContentMetadataMutations(key,ContentMetadataMutations().set("p06-etag",etag).set("p06-length",total))
    }
    override suspend fun enqueue(intent: DownloadIntent): Outcome<JobId> = withContext(Dispatchers.Main.immediate) {
        if(preparing.value) return@withContext Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY,"Another source is being prepared.",true))
        cancelOffer();preparationJob=currentCoroutineContext()[Job];preparingMutable.value=true
        try {
            val media=when(val result=withContext(Dispatchers.IO) { resolver.resolve(intent.source,ResolvePurpose.DOWNLOAD) }) {
                is Resolution.NativeMedia -> if(result.capabilities.downloadable) result else return@withContext Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"This source is not downloadable."))
                is Resolution.WebEmbed -> return@withContext Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,result.unavailableExplanation))
                is Resolution.Unavailable -> return@withContext Outcome.Failure(result.error)
            }
            val item=MediaItem.Builder().setUri(media.uri).setMimeType(media.mimeType).build()
            val candidate=DownloadHelper.Factory().setDataSourceFactory(scopedMediaDataSource(media,fixtureLoopback=fixtureLoopback)).setRenderersFactory(DefaultRenderersFactory(context)).create(item)
            helper=candidate
            withTimeout(25_000) { suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { scope.launch { candidate.release() } }
                candidate.prepare(object: DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper,manifestLoaded: Boolean) { if(continuation.isActive) continuation.resume(Unit) }
                    override fun onPrepareError(helper: DownloadHelper,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                })
            } }
            // Media3 1.11 progressive helpers can have a prepared period, but getTracks /
            // getTrackGroups are explicitly non-progressive APIs. Progressive is all-source.
            val progressive=androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(Uri.parse(media.uri),media.mimeType)==C.CONTENT_TYPE_OTHER
            val options=buildList {
                for(period in 0 until (if(progressive) 0 else candidate.periodCount)) {
                    val tracks=candidate.getTracks(period)
                    val groups=candidate.getTrackGroups(period)
                    for(groupIndex in 0 until groups.length) {
                        val group=groups[groupIndex]
                        if(group.type !in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_TEXT)) continue
                        val support=tracks.groups.firstOrNull { it.mediaTrackGroup==group }
                        for(track in 0 until group.length) if(support?.isTrackSupported(track)==true) {
                            val format=group.getFormat(track)
                            add(DownloadTrackRef(period,groupIndex,track,group.type,format.id,
                                "${when(group.type) { C.TRACK_TYPE_VIDEO -> format.height.takeIf { it>0 }?.let { "${it}p" } ?: "Source video";C.TRACK_TYPE_AUDIO -> "Audio";else -> "Subtitle" }} · ${format.label ?: format.language ?: format.sampleMimeType.orEmpty()}",format.height.takeIf { it>0 },format.language))
                        }
                    }
                }
            }
            preparedNative=media;offerMutable.value=DownloadOffer(++sequence,intent,options,progressive)
            Outcome.Failure(AppError(ErrorCode.CANCELED,"Confirm actual source tracks in Download selection."))
        } catch(e: CancellationException) { preparationJob=null;cancelOffer();throw e }
        catch(e: Exception) { preparationJob=null;cancelOffer();android.util.Log.w("EroflixP06","Track preparation failed: ${e.javaClass.simpleName}");Outcome.Failure(AppError(ErrorCode.NETWORK,"Download tracks could not be prepared (${e.javaClass.simpleName}). Retry this source.",true)) }
        finally { preparingMutable.value=false;preparationJob=null }
    }
    fun cancelOffer() { preparationJob?.cancel();preparationJob=null;preparingMutable.value=false;helper?.release();helper=null;preparedNative=null;offerMutable.value=null }
    suspend fun confirm(token: Long,chosen: List<DownloadTrackRef>): Outcome<JobId> = withContext(Dispatchers.Main.immediate) {
        val offer=offer.value?.takeIf { it.token==token } ?: return@withContext Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Selection expired. Prepare the source again."))
        val prepared=helper ?: return@withContext Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Source preparation is unavailable."))
        val media=preparedNative ?: return@withContext Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Source resolution is unavailable."))
        if(media.expiresAtEpochMs?.let { it<=System.currentTimeMillis() }==true) return@withContext Outcome.Failure(AppError(ErrorCode.EXPIRED,"Source expired while selecting. Cancel and prepare it again.",true))
        if(!offer.progressive && (chosen.isEmpty() || chosen.any { it !in offer.options })) return@withContext Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Choose actual prepared tracks."))
        if(!offer.progressive) {
            val required=offer.options.filter { it.type==C.TRACK_TYPE_VIDEO || it.type==C.TRACK_TYPE_AUDIO }.map { it.period to it.type }.toSet()
            if(!chosen.map { it.period to it.type }.containsAll(required)) return@withContext Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Choose video and audio for every available period."))
            for(period in 0 until prepared.periodCount) {
                prepared.clearTrackSelections(period)
                val groups=prepared.getTrackGroups(period)
                val params=TrackSelectionParameters.Builder(context).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO,true).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)
                chosen.filter { it.period==period }.groupBy { it.group }.forEach { (group,indexes) ->
                    if(group !in 0 until groups.length) return@withContext Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Prepared track group changed."))
                    params.setTrackTypeDisabled(indexes.first().type,false).addOverride(TrackSelectionOverride(groups[group],indexes.map { it.track }))
                }
                prepared.addTrackSelection(period,params.build())
            }
        }
        val preview=prepared.getDownloadRequest("preview",null)
        val id=downloadJobIdentity(offer.intent.source.id,preview.streamKeys.map { Triple(it.periodIndex,it.groupIndex,it.streamIndex) })
        records[id.value]?.let { cancelOffer();return@withContext Outcome.Success(it.id) }
        val estimate=media.validators?.lengthBytes?.takeIf { it>0 && offer.progressive }
        if(!hasDownloadSpace(context.filesDir.usableSpace,reservations(null),estimate ?: UNKNOWN_DOWNLOAD_RESERVATION)) return@withContext Outcome.Failure(AppError(ErrorCode.STORAGE_FULL,"Insufficient free space for this job, existing reservations and the 1 GiB floor.",true))
        if(records.size>=256) return@withContext Outcome.Failure(AppError(ErrorCode.STORAGE_FULL,"Manage existing downloads before adding more than 256 jobs."))
        val record=PersistedDownload(id,offer.intent.copy(selection=TrackSelection(chosen.mapNotNull { it.formatId }.toSet())),chosen,media,nextOrder++,estimatedBytes=estimate,streamKeys=preview.streamKeys.map { DownloadStreamRef(it.periodIndex,it.groupIndex,it.streamIndex) })
        when(val saved=withContext(Dispatchers.IO) { store.putDownload(record) }) { is Outcome.Failure -> return@withContext saved;is Outcome.Success -> Unit }
        records[id.value]=record
        val request=prepared.getDownloadRequest(id.value,null)
        manager.addDownload(request,STOP_SYSTEM)
        cancelOffer();publish();requestForeground();Outcome.Success(id)
    }
    private suspend fun save(record: PersistedDownload, admitted: () -> Boolean = { true }): Outcome<Unit> = persistence.withLock {
        if(!admitted()) return@withLock Outcome.Failure(AppError(ErrorCode.CANCELED,"Download intent changed before persistence."))
        val result=withContext(Dispatchers.IO) { store.putDownload(record) }
        if(result is Outcome.Success) records[record.id.value]=record
        result
    }
    private fun requestForeground() {
        if(pausedAll || !networkAllowed()) { scheduleRecovery();publish();return }
        try { context.startForegroundService(Intent(context,ProductionDownloadService::class.java)) }
        catch(_: RuntimeException) { scope.launch { systemPause("Android deferred the download service. Open Downloads and Resume.") } }
    }
    private fun scheduleRecovery() { DownloadRecoveryJob.schedule(context) }
    suspend fun foregroundStarted() {
        foregroundActive=true
        updatePolicy()
    }
    fun foregroundStopped() { foregroundActive=false;manager.pauseDownloads();publish() }
    private suspend fun updatePolicy() {
        if(!foregroundActive) {
            if(records.values.any { it.desired==DownloadDesired.RUN } && networkAllowed() && !pausedAll) requestForeground()
            return
        }
        if(pausedAll || !networkAllowed()) {
            manager.pauseDownloads();scheduleRecovery();publish();return
        }
        if(refreshing) { policyDirty=true;return }
        refreshing=true
        try {
            for(record in records.values.sortedBy { it.order }) {
                val download=snapshots[record.id.value]
                if(record.desired!=DownloadDesired.RUN || download?.state==Download.STATE_COMPLETED) continue
                if(download?.state==Download.STATE_DOWNLOADING || download?.stopReason==0 && download?.state==Download.STATE_QUEUED) continue
                refresh(record)
            }
            manager.resumeDownloads();publish()
        } finally { refreshing=false;if(policyDirty) { policyDirty=false;scope.launch { updatePolicy() } } }
    }
    private suspend fun refresh(record: PersistedDownload): Outcome<Unit> {
        val epoch=epochs[record.id.value] ?: 0L
        fun current()=(epochs[record.id.value] ?: 0L)==epoch && records[record.id.value]?.desired==DownloadDesired.RUN && foregroundActive && !pausedAll && networkAllowed()
        val resolved=withContext(Dispatchers.IO) { resolver.resolve(record.intent.source,ResolvePurpose.DOWNLOAD) }
        if(!current()) return Outcome.Failure(AppError(ErrorCode.CANCELED,"Download intent changed while resolving."))
        val fresh=when(val result=resolved) {
            is Resolution.NativeMedia -> result
            is Resolution.Unavailable -> { save(record.copy(desired=DownloadDesired.PAUSE_SYSTEM,reason=result.error.message),::current);return Outcome.Failure(result.error) }
            is Resolution.WebEmbed -> { val error=AppError(ErrorCode.UNSUPPORTED,result.unavailableExplanation);save(record.copy(desired=DownloadDesired.PAUSE_SYSTEM,reason=error.message),::current);return Outcome.Failure(error) }
        }
        if(!current()) return Outcome.Failure(AppError(ErrorCode.CANCELED,"Download intent changed while resolving."))
        if(!fresh.capabilities.downloadable) {
            val error=AppError(ErrorCode.UNSUPPORTED,"This source no longer permits native downloading.")
            save(record.copy(desired=DownloadDesired.PAUSE_SYSTEM,reason=error.message),::current);return Outcome.Failure(error)
        }
        val existing=snapshots[record.id.value]
        val safe=record.intent.source.kind==StoredSourceKind.FILE && resumeIdentityMatches(record.native,fresh)
        if(existing!=null && existing.bytesDownloaded>0 && (!safe || fresh.uri!=record.native.uri)) {
            val removed=CompletableDeferred<Unit>();pendingRemoval[record.id.value]=removed
            manager.removeDownload(record.id.value)
            try { withTimeout(15_000) { removed.await() } } catch(_: TimeoutCancellationException) { return Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY,"Wait for old partial media removal, then Resume.",true)) }
            messageMutable.value="Source identity could not safely resume. Partial media was removed before restarting."
        }
        // Existing exact stream keys are valid only if URI/fingerprint is unchanged; changed manifests need selection again.
        if(fresh.identityFingerprint!=record.native.identityFingerprint || (record.intent.source.kind!=StoredSourceKind.FILE && fresh.uri!=record.native.uri)) {
            save(record.copy(desired=DownloadDesired.PAUSE_SYSTEM,reason="Source changed. Remove and prepare tracks again."),::current);return Outcome.Failure(AppError(ErrorCode.PROVIDER_CHANGED,"Source changed. Prepare actual tracks again."))
        }
        val updated=record.copy(native=fresh,reason=null)
        when(val result=save(updated,::current)) { is Outcome.Failure -> return result;is Outcome.Success -> Unit }
        val request=existing?.request?.copyWithId(record.id.value) ?: DownloadRequest.Builder(record.id.value,Uri.parse(fresh.uri)).setMimeType(fresh.mimeType)
            .setStreamKeys(record.streamKeys.map { StreamKey(it.period,it.group,it.stream) }).build()
        // URI updates for a proven same progressive identity are represented by a rebuilt request.
        val rebuilt=DownloadRequest.Builder(request.id,Uri.parse(fresh.uri)).setMimeType(fresh.mimeType).setStreamKeys(request.streamKeys).setData(request.data).build()
        if(!current()) return Outcome.Failure(AppError(ErrorCode.CANCELED,"Download intent changed before transfer."))
        manager.addDownload(rebuilt,0);return Outcome.Success(Unit)
    }
    override suspend fun pause(id: JobId): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val record=records[id.value] ?: return@withContext missing()
        invalidate(id.value);manager.setStopReason(id.value,STOP_USER)
        when(val result=save(record.copy(desired=DownloadDesired.PAUSE_USER,reason="Paused by you"))) { is Outcome.Failure -> return@withContext result;is Outcome.Success -> Unit }
        manager.setStopReason(id.value,STOP_USER);publish();Outcome.Success(Unit)
    }
    override suspend fun resume(id: JobId): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val record=records[id.value] ?: return@withContext missing()
        invalidate(id.value)
        when(val result=save(record.copy(desired=DownloadDesired.RUN,reason=null))) { is Outcome.Failure -> return@withContext result;is Outcome.Success -> Unit }
        pausedAll=false;settings.edit().putBoolean("pauseAll",false).apply()
        manager.setStopReason(id.value,STOP_SYSTEM);requestForeground();if(foregroundActive) updatePolicy();publish();Outcome.Success(Unit)
    }
    override suspend fun remove(id: JobId): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val record=records[id.value] ?: return@withContext Outcome.Success(Unit)
        if((exportLeases[id.value] ?: 0)>0) return@withContext Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY,"Export is reading this download. Cancel or finish export before removing it."))
        if(playback.ui.value.source?.id==record.intent.source.id && playback.ui.value.phase!=PlaybackPhase.IDLE) return@withContext Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY,"Stop playback of this source before removing its download."))
        invalidate(id.value);manager.setStopReason(id.value,STOP_SYSTEM)
        when(val result=save(record.copy(desired=DownloadDesired.REMOVE,reason="Removing"))) { is Outcome.Failure -> return@withContext result;is Outcome.Success -> Unit }
        if(snapshots[id.value]==null) {
            withContext(Dispatchers.IO) { cache.keys.filter { it.startsWith("${id.value}|") }.forEach { cache.removeResource(it) } }
            when(val result=withContext(Dispatchers.IO) { store.deleteDownload(id) }) { is Outcome.Failure -> return@withContext result;is Outcome.Success -> records.remove(id.value) }
        } else manager.removeDownload(id.value)
        publish();Outcome.Success(Unit)
    }
    override suspend fun openOffline(id: JobId): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val record=records[id.value] ?: return@withContext missing()
        val download=snapshots[id.value] ?: return@withContext missing()
        if(download.state!=Download.STATE_COMPLETED) return@withContext Outcome.Failure(AppError(ErrorCode.LOCAL_ONLY,"This download is not complete."))
        playback.openCached(record.intent.title,record.intent.source,download.request.toMediaItem(),cacheFactory(record,true),record.native.identityFingerprint)
    }
    suspend fun pauseAll() {
        pausedAll=true;settings.edit().putBoolean("pauseAll",true).apply()
        records.values.filter { it.desired==DownloadDesired.RUN }.forEach { invalidate(it.id.value);save(it.copy(desired=DownloadDesired.PAUSE_USER,reason="Paused all by you"));manager.setStopReason(it.id.value,STOP_USER) }
        manager.pauseDownloads();publish()
    }
    suspend fun resumeAll() {
        pausedAll=false;settings.edit().putBoolean("pauseAll",false).apply()
        records.values.filter { it.desired!=DownloadDesired.REMOVE && snapshots[it.id.value]?.state!=Download.STATE_COMPLETED }.forEach {
            invalidate(it.id.value);save(it.copy(desired=DownloadDesired.RUN,reason=null));manager.setStopReason(it.id.value,STOP_SYSTEM)
        }
        requestForeground();if(foregroundActive) updatePolicy();publish()
    }
    suspend fun systemPause(reason: String) {
        manager.pauseDownloads()
        records.values.filter { it.desired==DownloadDesired.RUN }.forEach { invalidate(it.id.value);save(it.copy(desired=DownloadDesired.PAUSE_SYSTEM,reason=reason));manager.setStopReason(it.id.value,STOP_SYSTEM) }
        messageMutable.value=reason;publish()
    }
    fun setWifiOnly(value: Boolean) { wifiMutable.value=value;settings.edit().putBoolean("wifiOnly",value).apply();scope.launch { updatePolicy() } }
    fun setParallel(value: Int) { parallelMutable.value=value.coerceIn(1,4);settings.edit().putInt("parallel",parallel.value).apply();manager.maxParallelDownloads=if(playback.ui.value.phase==PlaybackPhase.PLAYING) 1 else parallel.value }
    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            cancelOffer();foregroundActive=false;manager.pauseDownloads();manager.release()
            runCatching { connectivity.unregisterNetworkCallback(callback) };scope.cancel()
        }
        withContext(Dispatchers.IO) { executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);cache.release();database.close() }
    }
    suspend fun acquireExport(id: JobId): Outcome<CompletedDownloadLease> = withContext(Dispatchers.Main.immediate) {
        val record=records[id.value] ?: return@withContext missing()
        val download=snapshots[id.value]?.takeIf { it.state==Download.STATE_COMPLETED }
            ?: return@withContext Outcome.Failure(AppError(ErrorCode.LOCAL_ONLY,"Complete this download before exporting."))
        if(record.desired==DownloadDesired.REMOVE) return@withContext missing()
        if(record.selected.any { it.type==C.TRACK_TYPE_TEXT }) return@withContext Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"MP4 export currently supports video/audio only. Subtitle-selected jobs remain playable offline; prepare a video/audio-only download to export."))
        exportLeases[id.value]=(exportLeases[id.value] ?: 0)+1
        var released=false
        Outcome.Success(CompletedDownloadLease(record,download.request.toMediaItem(),cacheFactory(record,true),download.bytesDownloaded,if(record.intent.source.kind==StoredSourceKind.HLS) (listOf(record.native.uri)+cache.keys.filter { it.startsWith("${id.value}|") && cache.isCached(it, 0, 1) }.map { it.substringAfter('|') }.filter { Uri.parse(it).path?.endsWith(".m3u8")==true }).distinct() else emptyList()) {
            if(!released) { released=true;val count=(exportLeases[id.value] ?: 1)-1;if(count<=0) exportLeases.remove(id.value) else exportLeases[id.value]=count }
        })
    }
    fun reserveStaging(id: String,bytes: Long): Boolean {
        if(bytes<0 || !hasDownloadSpace(context.filesDir.usableSpace,reservations(null),bytes)) return false
        stagingReservations[id]=bytes;return true
    }
    fun releaseStaging(id: String) { stagingReservations.remove(id) }
    fun stagingSpaceAvailable(id: String,remainingBytes: Long)=hasDownloadSpace(context.filesDir.usableSpace,
        reservations(null).let { all -> all.toMutableList().apply { stagingReservations[id]?.let { remove(it) } } },remainingBytes)
    private fun missing()=Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Download is unavailable."))
    private fun publish() {
        manager.currentDownloads.forEach { snapshots[it.request.id]=it;transferred[it.request.id]=it.bytesDownloaded }
        mutable.value=records.values.sortedBy { it.order }.map { record ->
            val d=snapshots[record.id.value]
            val status=when {
                record.desired==DownloadDesired.REMOVE -> DownloadStatus.REMOVING
                d?.state==Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
                record.desired==DownloadDesired.PAUSE_USER || d?.stopReason==STOP_USER || pausedAll -> DownloadStatus.PAUSED_USER
                record.desired==DownloadDesired.PAUSE_SYSTEM -> DownloadStatus.PAUSED_SYSTEM
                !networkAllowed() -> DownloadStatus.WAITING_NETWORK
                d?.state==Download.STATE_FAILED -> DownloadStatus.FAILED_RETRYABLE
                d?.state==Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
                else -> DownloadStatus.QUEUED
            }
            DownloadJob(record.id,record.intent,status,d?.bytesDownloaded ?: 0,d?.contentLength?.takeIf { it>=0 } ?: record.estimatedBytes,
                record.reason?.let { AppError(ErrorCode.LOCAL_ONLY,it,true) })
        }
    }
}
