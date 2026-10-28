package tech.imitravanu.eroflix.core.media

import android.content.*
import android.media.*
import android.media.metrics.LogSessionId
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ExportApplication { val exportOwner: StateFlow<ProductionExports?> }

/** One durable export worker. Inputs never have upstream access. Output is staged before SAF. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ProductionExports private constructor(private val context: Context,private val store: ExportIntentStore,
    private val downloads: ProductionDownloads,private val directory: File) : ExportEngine {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val queuedLeases=mutableMapOf<String,CompletedDownloadLease>()
    private val records=linkedMapOf<String,PersistedExport>()
    private val mutable=MutableStateFlow<List<ExportJob>>(emptyList())
    override val exports=mutable.asStateFlow()
    private var worker: Job?=null
    private var activeId: JobId?=null
    private var serviceActive=false
    val isForegroundActive get()=serviceActive
    private var transformer: Transformer?=null
    companion object {
        suspend fun create(context: Context,store: ExportIntentStore,downloads: ProductionDownloads,
            directory: File=File(context.filesDir,"export-staging")): ProductionExports {
            val owner=ProductionExports(context.applicationContext,store,downloads,directory)
            val initial=when(val result=store.exportIntents()) { is Outcome.Success -> result.value;is Outcome.Failure -> error(result.error.message) }
            withContext(Dispatchers.IO) { directory.mkdirs();File(directory,".nomedia").createNewFile() }
            withContext(Dispatchers.Main.immediate) {
                initial.forEach { original ->
                    val interrupted=original.job.status in listOf(ExportStatus.QUEUED,ExportStatus.EXPORTING)
                    val record=if(interrupted) original.copy(job=original.job.copy(status=ExportStatus.FAILED,error=AppError(ErrorCode.EXPORT_FAILED,"Export was interrupted. The download is intact; remove any partial destination and choose Export again.",true)),stagingReference=null) else original
                    if(interrupted) { withContext(Dispatchers.IO) { File(directory,"${record.job.id.value}.mp4").delete() };store.putExport(record) }
                    owner.records[record.job.id.value]=record
                }
                owner.publish()
            }
            return owner
        }
    }
    private fun publish() { mutable.value=records.values.map { it.job } }
    private suspend fun save(record: PersistedExport) {
        when(val result=store.putExport(record)) { is Outcome.Failure -> throw ExportFailure("Export intent could not be saved. Keep the download and retry.");is Outcome.Success -> Unit }
        records[record.job.id.value]=record;publish()
    }
    override suspend fun export(downloadId: JobId,destination: String): Outcome<JobId> = withContext(Dispatchers.Main.immediate) {
        if(Uri.parse(destination).scheme!="content") return@withContext Outcome.Failure(AppError(ErrorCode.INVALID_QUERY,"Choose a document destination using the file picker."))
        if(records.values.any { it.job.status in listOf(ExportStatus.QUEUED,ExportStatus.EXPORTING) }) return@withContext Outcome.Failure(AppError(ErrorCode.DATABASE_BUSY,"One export runs at a time. Wait or cancel the current export."))
        val lease=when(val result=downloads.acquireExport(downloadId)) { is Outcome.Failure -> return@withContext result;is Outcome.Success -> result.value }
        val id=JobId(UUID.randomUUID().toString())
        val record=PersistedExport(ExportJob(id,downloadId,destination,ExportStatus.QUEUED),null,System.currentTimeMillis())
        queuedLeases[id.value]=lease
        try {
            save(record)
            try { context.contentResolver.takePersistableUriPermission(Uri.parse(destination),Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_: SecurityException) { /* temporary grant may still support this foreground export */ }
            context.startForegroundService(Intent(context,ProductionExportService::class.java))
            Outcome.Success(id)
        } catch(e: Exception) {
            queuedLeases.remove(id.value)?.release()
            records[id.value]?.let { runCatching { save(it.copy(job=it.job.copy(status=ExportStatus.FAILED,error=AppError(ErrorCode.EXPORT_FAILED,"Android could not start export. Open Downloads and choose Export again.",true)))) } }
            Outcome.Failure(AppError(ErrorCode.EXPORT_FAILED,"Export could not start. The download is intact.",true))
        }
    }
    fun foregroundStarted() { serviceActive=true;if(worker?.isActive!=true) worker=scope.launch {
        records.values.firstOrNull { it.job.status==ExportStatus.QUEUED }?.let { runExport(it) }
    } }
    fun foregroundStopped() { serviceActive=false;worker?.cancel() }
    suspend fun timeout() { activeId?.let { cancel(it) } }
    override suspend fun cancel(exportId: JobId): Outcome<Unit> = withContext(Dispatchers.Main.immediate) {
        val record=records[exportId.value] ?: return@withContext Outcome.Failure(AppError(ErrorCode.NOT_FOUND,"Export not found."))
        if(record.job.status==ExportStatus.EXPORTED) return@withContext Outcome.Success(Unit)
        if(activeId==exportId) worker?.cancel()
        queuedLeases.remove(exportId.value)?.release()
        try { save(record.copy(job=record.job.copy(status=ExportStatus.CANCELED,error=AppError(ErrorCode.CANCELED,"Export canceled; the download is intact.")),stagingReference=null));Outcome.Success(Unit) }
        catch(_: Exception) { Outcome.Failure(AppError(ErrorCode.EXPORT_FAILED,"Could not save cancellation. Reopen Downloads to reconcile.",true)) }
    }
    private suspend fun runExport(initial: PersistedExport) {
        val id=initial.job.id;activeId=id
        val staging=File(directory,"${id.value}.mp4")
        var lease: CompletedDownloadLease?=null
        var copied=false
        var destinationOpened=false
        var phase="lease"
        try {
            require(serviceActive)
            lease=queuedLeases.remove(id.value) ?: when(val result=downloads.acquireExport(initial.job.downloadId)) { is Outcome.Success -> result.value;is Outcome.Failure -> throw ExportFailure(result.error.message) }
            val reserve=exportReservation(lease.bytes) ?: throw ExportFailure("Export size exceeds safe storage accounting.")
            if(!downloads.reserveStaging(id.value,reserve)) throw ExportFailure("Insufficient space for staging, destination duplication, other jobs and the 1 GiB floor.")
            save(initial.copy(job=initial.job.copy(status=ExportStatus.EXPORTING),stagingReference=staging.name))
            val started=android.os.SystemClock.elapsedRealtime()
            phase="cached-manifest"
            withContext(Dispatchers.IO) { validateCachedManifests(lease) }
            phase="remux"
            val result=remux(lease,staging,id.value,reserve)
            if(result.audioEncoderName!=null || result.videoEncoderName!=null) throw ExportFailure("This source requires encoding; normal export only remuxes supported video/audio.")
            phase="staging-validation"
            val info=withContext(Dispatchers.IO) { validateExport(staging) }
            if(!downloads.stagingSpaceAvailable(id.value,staging.length())) throw ExportFailure("Storage reserve reached before destination copy.")
            withContext(Dispatchers.IO) {
                phase="destination-copy"
                context.contentResolver.openOutputStream(Uri.parse(initial.job.destination),"wt")?.use { output ->
                    destinationOpened=true
                    staging.inputStream().use { input ->
                        val buffer=ByteArray(256*1024);var bytes=0L;var reported=0L
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val n=input.read(buffer);if(n<0) break
                            output.write(buffer,0,n);bytes+=n
                            if(bytes-reported>=1024*1024) {
                                if(!downloads.stagingSpaceAvailable(id.value,staging.length())) throw ExportFailure("Storage reserve reached during destination copy.")
                                reported=bytes
                                withContext(Dispatchers.Main.immediate) { val old=records[id.value]!!;records[id.value]=old.copy(job=old.job.copy(bytesCopied=bytes,totalBytes=staging.length()));publish() }
                            }
                        }
                        output.flush();if(bytes!=staging.length()) throw ExportFailure("Destination copy length mismatch.")
                    }
                } ?: throw ExportFailure("Document provider did not provide a writable stream.")
                // Reopen the finished destination independently. Non-seekable/read-denied providers
                // retain the original download and report an unverified export rather than success.
                phase="destination-validation"
                context.contentResolver.openAssetFileDescriptor(Uri.parse(initial.job.destination),"r")?.use { descriptor ->
                    val extractor=MediaExtractor()
                    try { extractor.setDataSource(descriptor.fileDescriptor,descriptor.startOffset,descriptor.length.takeIf { it>=0 } ?: staging.length());validateExtractor(extractor,info.durationUs) } finally { extractor.release() }
                } ?: throw ExportFailure("Cannot reopen destination for independent validation.")
            }
            copied=true
            val old=records[id.value]!!
            save(old.copy(job=old.job.copy(status=ExportStatus.EXPORTED,bytesCopied=staging.length(),totalBytes=staging.length(),error=null),stagingReference=null))
            android.util.Log.i("EroflixP07","REMUX_COMPLETE tracks=${info.tracks} durationMs=${info.durationUs/1000} bytes=${staging.length()} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} encoders=none destinationValidated=true")
        } catch(e: CancellationException) {
            withContext(NonCancellable) { val old=records[id.value] ?: initial;runCatching { save(old.copy(job=old.job.copy(status=ExportStatus.CANCELED,error=AppError(ErrorCode.CANCELED,"Export stopped; the download is intact.")),stagingReference=null)) } }
        } catch(e: Exception) {
            android.util.Log.w("EroflixP07","EXPORT_FAILED phase=$phase type=${e.javaClass.simpleName} frames=${e.stackTrace.take(8).joinToString { "${it.className}.${it.methodName}:${it.lineNumber}" }}")
            val old=records[id.value] ?: initial
            runCatching { save(old.copy(job=old.job.copy(status=ExportStatus.FAILED,error=AppError(ErrorCode.EXPORT_FAILED,(if(e is ExportFailure) e.message.orEmpty().take(220) else "Export failed at $phase (${e.javaClass.simpleName}). The download is intact.")+" Remove any partial destination before retry.",true)),stagingReference=null)) }
        } finally {
            withContext(NonCancellable+Dispatchers.Main.immediate) { transformer?.cancel();transformer=null;lease?.release();downloads.releaseStaging(id.value);activeId=null }
            withContext(NonCancellable+Dispatchers.IO) {
                staging.delete()
                if(destinationOpened && !copied) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver,Uri.parse(initial.job.destination)) }
            }
        }
    }
    suspend fun close() = withContext(Dispatchers.Main.immediate) { worker?.cancelAndJoin();transformer?.cancel();transformer=null;queuedLeases.values.forEach { it.release() };queuedLeases.clear();scope.cancel() }
    private fun validateCachedManifests(lease: CompletedDownloadLease) {
        lease.manifestUris.forEach { uri ->
            val source=lease.dataSourceFactory.createDataSource()
            try {
                source.open(androidx.media3.datasource.DataSpec(Uri.parse(uri)))
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=source.read(buffer,0,buffer.size);if(n<0) break;requireExport(output.size()+n<=512*1024,"Manifest exceeds bounded export inspection.");output.write(buffer,0,n) }
                requireExport(exportManifestAllowed(output.toString("UTF-8")),"Encrypted, discontinuous, live or partial HLS is not supported by standalone export. The download remains available offline.")
            } finally { source.close() }
        }
    }
    private suspend fun remux(lease: CompletedDownloadLease,output: File,id: String,reserve: Long): ExportResult = suspendCancellableCoroutine { continuation ->
        val assetFactory=DefaultAssetLoaderFactory(context,DefaultDecoderFactory.Builder(context).build(),Clock.DEFAULT,
            DefaultMediaSourceFactory(lease.dataSourceFactory),DataSourceBitmapLoader(DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),lease.dataSourceFactory))
        val owner=Transformer.Builder(context).setAssetLoaderFactory(assetFactory).setEncoderFactory(object: Codec.EncoderFactory {
            override fun createForAudioEncoding(format: Format,logSessionId: LogSessionId?): Codec=throw ExportException.createForUnexpected(UnsupportedOperationException("Audio transcoding is not enabled for standalone export."))
            override fun createForVideoEncoding(format: Format,logSessionId: LogSessionId?): Codec=throw ExportException.createForUnexpected(UnsupportedOperationException("Video transcoding is not enabled for standalone export."))
        }).addListener(object: Transformer.Listener {
            override fun onCompleted(composition: Composition,result: ExportResult) { if(continuation.isActive) continuation.resume(result) }
            override fun onError(composition: Composition,result: ExportResult,exception: ExportException) { if(continuation.isActive) continuation.resumeWithException(ExportFailure("This source could not be safely remuxed (${exception.errorCode}). The completed download is intact.")) }
        }).build()
        transformer=owner
        continuation.invokeOnCancellation { scope.launch { owner.cancel() } }
        val edited=EditedMediaItem.Builder(lease.mediaItem).build()
        val composition=Composition.Builder(EditedMediaItemSequence.Builder(edited).build()).setTransmuxAudio(true).setTransmuxVideo(true).build()
        scope.launch {
            while(continuation.isActive) {
                delay(500)
                if(output.length()>reserve/2 || !downloads.stagingSpaceAvailable(id,(reserve-output.length()).coerceAtLeast(0))) {
                    owner.cancel()
                    if(continuation.isActive) continuation.resumeWithException(ExportFailure("Export exceeded its reserved space; the download is intact."))
                    break
                }
            }
        }
        try { owner.start(composition,output.absolutePath) } catch(_: Exception) { if(continuation.isActive) continuation.resumeWithException(ExportFailure("This source could not start cache-only remux. The download is intact.")) }
    }
}

private class ExportFailure(message: String): IOException(message)
private fun requireExport(condition: Boolean,message: String) { if(!condition) throw ExportFailure(message) }

// A remux can be larger than its input due to MP4 headers and indexes. Reserve
// that overhead for both the staged file and its destination copy.
internal fun exportReservation(bytes: Long): Long? {
    if (bytes <= 0) return null
    val overhead = maxOf(1024L * 1024, bytes / 20)
    if (bytes > Long.MAX_VALUE / 2 - overhead) return null
    return (bytes + overhead) * 2
}
internal data class ExportInspection(val tracks: Int,val durationUs: Long)
internal fun validateExport(file: File): ExportInspection {
    require(file.length()>0) { "Staged export is empty." }
    val extractor=MediaExtractor()
    try { extractor.setDataSource(file.absolutePath);return validateExtractor(extractor,null) } finally { extractor.release() }
}
internal fun validateExtractor(extractor: MediaExtractor,expectedDuration: Long?): ExportInspection {
    require(extractor.trackCount in 1..2) { "Export must contain one video and/or one audio track." }
    var duration=0L;val types=mutableSetOf<String>()
    val buffer=java.nio.ByteBuffer.allocate(2*1024*1024)
    for(track in 0 until extractor.trackCount) {
        val format=extractor.getTrackFormat(track);val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
        require(mime in setOf("video/avc","audio/mp4a-latm")) { "Standalone export currently validates H264/AAC only." }
        require(types.add(mime.substringBefore('/'))) { "Multiple tracks of the same type are not supported by this export path." }
        val trackDuration=format.getLong(MediaFormat.KEY_DURATION);require(trackDuration>0) { "Export duration missing." };duration=maxOf(duration,trackDuration)
        extractor.selectTrack(track);extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);require(extractor.readSampleData(buffer,0)>0) { "Export track has no samples." }
        extractor.seekTo(trackDuration/2,MediaExtractor.SEEK_TO_CLOSEST_SYNC);require(extractor.readSampleData(buffer,0)>0) { "Export is not seekable." };extractor.unselectTrack(track)
    }
    if(expectedDuration!=null) require(kotlin.math.abs(duration-expectedDuration)<=100_000) { "Destination duration differs from validated staging." }
    return ExportInspection(extractor.trackCount,duration)
}

internal fun exportManifestAllowed(text: String): Boolean {
    if(!text.trimStart().startsWith("#EXTM3U")) return false
    if(text.lineSequence().any { line -> line.startsWith("#EXT-X-DISCONTINUITY") || line.startsWith("#EXT-X-GAP") || line.startsWith("#EXT-X-PART") || ((line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) && !line.contains("METHOD=NONE")) }) return false
    return text.contains("#EXT-X-STREAM-INF:") || text.contains("#EXT-X-MEDIA:") || text.contains("#EXT-X-ENDLIST")
}
