package tech.imitravanu.eroflix.testing

import kotlinx.coroutines.flow.MutableStateFlow
import tech.imitravanu.eroflix.model.*

/** Explicit unsupported results prevent simulated playback or downloads being mistaken for real media. */
class FixtureSourceResolver : SourceResolver {
    override suspend fun resolve(source: SourceHandle, purpose: ResolvePurpose) = Resolution.Unavailable(AppError(ErrorCode.UNSUPPORTED, "No media is attached to this fixture."))
}
class FixturePlayerControl : PlayerControl {
    override val state = MutableStateFlow(PlayerState())
    override suspend fun open(source: SourceHandle, startPositionMs: Long) = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Fixture playback is unavailable."))
    override fun pause() { state.value = state.value.copy(playing = false) }
    override fun seekTo(positionMs: Long) { require(positionMs >= 0) }
    override fun setSpeed(multiplier: Float) { require(multiplier > 0 && multiplier.isFinite()) }
    override fun stop() { state.value = PlayerState() }
}
class FixtureDownloadController : DownloadController {
    override val jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    private fun unsupported() = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Downloads are unavailable in the foundation fixture."))
    override suspend fun enqueue(intent: DownloadIntent) = unsupported()
    override suspend fun pause(id: JobId) = unsupported()
    override suspend fun resume(id: JobId) = unsupported()
    override suspend fun remove(id: JobId) = unsupported()
    override suspend fun openOffline(id: JobId) = unsupported()
}
class FixtureExportEngine : ExportEngine {
    override val exports = MutableStateFlow<List<ExportJob>>(emptyList())
    override suspend fun export(downloadId: JobId, destination: String) = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Export is unavailable in the foundation fixture."))
    override suspend fun cancel(exportId: JobId) = Outcome.Failure(AppError(ErrorCode.NOT_FOUND, "No export exists."))
}
class FixtureGenerationStore : CatalogGenerationStore {
    override val active = MutableStateFlow<GenerationId?>(GenerationId("fixture-v1"))
    override suspend fun stage(manifest: CatalogManifest, localArtifact: String) = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Fixture catalog is fixed."))
    override suspend fun activate(generation: GenerationId) = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Fixture catalog is fixed."))
    override suspend fun rollback() = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "No retained generation exists."))
    override suspend fun retainedGenerations() = listOfNotNull(active.value)
}
class FixtureCatalogUpdater : CatalogUpdater {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override suspend fun check(): Outcome<CatalogManifest?> = Outcome.Success(null)
    override suspend fun install(manifest: CatalogManifest) = Outcome.Failure(AppError(ErrorCode.UNSUPPORTED, "Fixture updates are unavailable."))
    override fun cancel() { state.value = UpdateState.Idle }
}
