package tech.imitravanu.eroflix.model

import kotlinx.coroutines.flow.StateFlow

sealed interface FacetValue {
    data class Known(val key: String) : FacetValue { init { require(key.isNotBlank()) } }
    data object Unknown : FacetValue
}
enum class CatalogSort { TITLE, YEAR_NEWEST, YEAR_OLDEST }
enum class EntityKind { PERFORMER, STUDIO, SERIES, COUNTRY, DECADE }
data class EntityRef(val kind: EntityKind, val key: String, val label: String)
data class DurationFilter(val minMs: Long? = null, val maxMs: Long? = null, val includeUnknown: Boolean = true, val onlyUnknown: Boolean = false) {
    init { require(minMs == null || minMs >= 0); require(maxMs == null || maxMs >= 0); require(minMs == null || maxMs == null || minMs <= maxMs) }
}
data class QueryStamp(val queryKey: String, val generation: GenerationId, val personalRevision: Long)
data class PageCursor(val stamp: QueryStamp, val lastSortValue: String, val lastTitleId: TitleId)
data class CatalogQuery(
    val search: String = "", val providers: Set<FacetValue> = emptySet(),
    val countries: Set<FacetValue> = emptySet(), val decades: Set<FacetValue> = emptySet(),
    val studios: Set<FacetValue> = emptySet(), val entity: EntityRef? = null,
    val duration: DurationFilter = DurationFilter(), val sort: CatalogSort = CatalogSort.TITLE,
    val cursor: PageCursor? = null, val limit: Int = 60,
) {
    init { require(limit in 1..120) }
    /** Cursor is excluded; generation and personal revision are validated separately. */
    fun key(): String {
        fun Set<FacetValue>.canonical() = map {
            when (it) { is FacetValue.Known -> "k${it.key.length}:${it.key}"; FacetValue.Unknown -> "u" }
        }.sorted().joinToString(";")
        return stableDigest(search, providers.canonical(), countries.canonical(), decades.canonical(),
            studios.canonical(), entity?.kind?.name.orEmpty(), entity?.key.orEmpty(),
            duration.minMs?.toString().orEmpty(), duration.maxMs?.toString().orEmpty(),
            duration.includeUnknown.toString(), duration.onlyUnknown.toString(), sort.name, limit.toString())
    }
}
data class TitleSummary(val id: TitleId, val title: String, val year: Int? = null,
    val durationMs: Long? = null, val posterUrl: String? = null)
data class TitleDetails(val summary: TitleSummary, val description: String?,
    val sources: List<SourceHandle>, val entities: List<EntityRef>, val provenance: String?)
data class Page<T>(val items: List<T>, val next: PageCursor?, val stamp: QueryStamp)
data class FacetOption(val value: FacetValue, val label: String, val titleCount: Long, val selected: Boolean)
data class CatalogFacets(val providers: List<FacetOption>, val countries: List<FacetOption>,
    val decades: List<FacetOption>, val studios: List<FacetOption>, val knownDurationAvailable: Boolean,
    val stamp: QueryStamp)
data class CatalogMetadata(val generation: GenerationId, val titleCount: Long, val sourceCount: Long)
data class CatalogCount(val stamp: QueryStamp, val titleCount: Long)
interface CatalogRepository {
    suspend fun metadata(): Outcome<CatalogMetadata> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Catalog metadata unavailable."))
    suspend fun count(query: CatalogQuery): Outcome<CatalogCount> = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Count unavailable."))
    val activeGeneration: StateFlow<GenerationId?>
    suspend fun query(query: CatalogQuery): Outcome<Page<TitleSummary>>
    suspend fun facets(query: CatalogQuery): Outcome<CatalogFacets>
    suspend fun details(id: TitleId): Outcome<TitleDetails>
}

data class CatalogManifest(val formatVersion: Int, val schemaVersion: Int, val generationId: GenerationId,
    val createdAtEpochMs: Long, val titleCount: Long, val sourceCount: Long, val minAppVersionCode: Long,
    val artifactUrl: String, val compression: String, val compressedBytes: Long, val uncompressedBytes: Long,
    val compressedSha256: String, val uncompressedSha256: String)
interface CatalogGenerationStore {
    val active: StateFlow<GenerationId?>
    suspend fun stage(manifest: CatalogManifest, localArtifact: String): Outcome<GenerationId>
    suspend fun activate(generation: GenerationId): Outcome<Unit>
    suspend fun rollback(): Outcome<GenerationId>
    suspend fun retainedGenerations(): List<GenerationId>
}
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Working(val completedBytes: Long, val totalBytes: Long?) : UpdateState
    data class Available(val manifest: CatalogManifest) : UpdateState
    data class Failed(val error: AppError) : UpdateState
}
interface CatalogUpdater {
    val state: StateFlow<UpdateState>
    suspend fun check(): Outcome<CatalogManifest?>
    suspend fun install(manifest: CatalogManifest): Outcome<Unit>
    fun cancel()
}
