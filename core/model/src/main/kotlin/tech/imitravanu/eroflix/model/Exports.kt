package tech.imitravanu.eroflix.model

/** Persistent export state is independent of Media3's completed download state. */
data class PersistedExport(val job: ExportJob,val stagingReference: String?,val createdAtMs: Long)
interface ExportIntentStore {
    suspend fun exportIntents(): Outcome<List<PersistedExport>>
    suspend fun putExport(record: PersistedExport): Outcome<Unit>
}
