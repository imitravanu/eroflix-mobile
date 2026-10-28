package tech.imitravanu.eroflix.model

/** Group/period indexes are scoped to this freshly prepared manifest, never global format IDs. */
data class DownloadTrackRef(val period: Int,val group: Int,val track: Int,val type: Int,
    val formatId: String?,val label: String,val height: Int?=null,val language: String?=null)
data class DownloadStreamRef(val period: Int,val group: Int,val stream: Int)
enum class DownloadDesired { RUN, PAUSE_USER, PAUSE_SYSTEM, REMOVE }
data class PersistedDownload(val id: JobId,val intent: DownloadIntent,val selected: List<DownloadTrackRef>,
    val native: Resolution.NativeMedia,val order: Long,val desired: DownloadDesired=DownloadDesired.RUN,
    val reason: String?=null,val estimatedBytes: Long?=null,val streamKeys: List<DownloadStreamRef> = emptyList())
interface DownloadIntentStore {
    suspend fun downloads(): Outcome<List<PersistedDownload>>
    suspend fun putDownload(record: PersistedDownload): Outcome<Unit>
    suspend fun deleteDownload(id: JobId): Outcome<Unit>
}
