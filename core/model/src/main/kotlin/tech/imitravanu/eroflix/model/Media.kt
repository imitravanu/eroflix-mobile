package tech.imitravanu.eroflix.model

import kotlinx.coroutines.flow.StateFlow

enum class StoredSourceKind { FILE, HLS, EMBED }
enum class ResolvePurpose { PLAYBACK, DOWNLOAD }
data class SourceHandle(val titleId: TitleId, val id: SourceId, val provider: String,
    val originalUrl: String, val kind: StoredSourceKind, val externalId: String? = null,
    val adapterKey: String? = null, val headerPolicyKey: String? = null, val provenance: String? = null)
data class RequestScope(val allowedHosts: Set<String>, val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap())
data class MediaTrack(val id: String, val label: String, val mimeType: String?, val height: Int? = null,
    val language: String? = null, val bitrate: Int? = null)
data class MediaCapabilities(val nativePlayback: Boolean, val downloadable: Boolean, val exportable: Boolean)
data class ResourceValidators(val etag: String? = null,val lastModified: String? = null,
    val lengthBytes: Long? = null,val acceptsRanges: Boolean = false)
sealed interface Resolution {
    data class NativeMedia(val uri: String, val mimeType: String?, val requestScopes: List<RequestScope>,
        val expiresAtEpochMs: Long?, val identityFingerprint: String, val provenance: String?,
        val tracks: List<MediaTrack>, val capabilities: MediaCapabilities,
        val validators: ResourceValidators? = null) : Resolution
    data class WebEmbed(val entryUri: String, val policyId: String, val unavailableExplanation: String) : Resolution
    data class Unavailable(val error: AppError) : Resolution
}
interface SourceResolver { suspend fun resolve(source: SourceHandle, purpose: ResolvePurpose): Resolution }
data class PlayerState(val titleId: TitleId? = null, val positionMs: Long = 0,
    val durationMs: Long? = null, val playing: Boolean = false, val error: AppError? = null)
interface PlayerControl {
    val state: StateFlow<PlayerState>
    suspend fun open(source: SourceHandle, startPositionMs: Long = 0): Outcome<Unit>
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(multiplier: Float)
    fun stop()
}
data class TrackSelection(val trackIds: Set<String>)
data class DownloadIntent(val title: TitleSummary, val source: SourceHandle, val selection: TrackSelection) {
    init { require(title.id == source.titleId) { "Download source must belong to the requested title." } }
}
enum class DownloadStatus { QUEUED, RESOLVING, WAITING_NETWORK, DOWNLOADING, PAUSED_USER,
    PAUSED_SYSTEM, FAILED_RETRYABLE, FAILED_FINAL, COMPLETED, REMOVING }
data class DownloadJob(val id: JobId, val intent: DownloadIntent, val status: DownloadStatus,
    val downloadedBytes: Long, val totalBytes: Long?, val error: AppError? = null)
interface DownloadController {
    val jobs: StateFlow<List<DownloadJob>>
    suspend fun enqueue(intent: DownloadIntent): Outcome<JobId>
    suspend fun pause(id: JobId): Outcome<Unit>
    suspend fun resume(id: JobId): Outcome<Unit>
    suspend fun remove(id: JobId): Outcome<Unit>
    suspend fun openOffline(id: JobId): Outcome<Unit>
}
enum class ExportStatus { QUEUED, EXPORTING, FAILED, EXPORTED, CANCELED }
data class ExportJob(val id: JobId, val downloadId: JobId, val destination: String,
    val status: ExportStatus, val error: AppError? = null, val bytesCopied: Long = 0, val totalBytes: Long? = null)
interface ExportEngine {
    val exports: StateFlow<List<ExportJob>>
    suspend fun export(downloadId: JobId, destination: String): Outcome<JobId>
    suspend fun cancel(exportId: JobId): Outcome<Unit>
}
