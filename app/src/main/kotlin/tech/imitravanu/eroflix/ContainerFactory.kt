package tech.imitravanu.eroflix

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.core.data.*
import tech.imitravanu.eroflix.core.network.CatalogSourceResolver
import tech.imitravanu.eroflix.model.*
import java.io.File

suspend fun createAppContainer(context: Context,progress: (String)->Unit): AppContainer {
    val generations=GenerationStorage(File(context.filesDir,"catalog-generations"))
    progress("Opening your library…")
    val personal=PersonalStorage(File(context.filesDir,"personal/personal.db"),generations,context.assets.open("schema/personal-v1.sql").bufferedReader().use { it.readText() })
    personal.initialize()
    try {
        generations.initialize()
        val manifest=parseCatalogManifest(context.assets.open("catalog/manifest.json").bufferedReader().use { it.readText() })
        if(generations.needsBundledGeneration(manifest)) {
            progress("Preparing offline catalog…")
            val compressed=File(context.cacheDir,"bundled-catalog.db.gz")
            try {
                context.assets.open("catalog/catalog.bin").use { input -> compressed.outputStream().use { output -> input.copyTo(output,256*1024) } }
                progress("Extracting and verifying offline catalog (${manifest.titleCount} titles)…")
                coroutineScope {
                    val observer=launch { generations.progress.filterNotNull().collect { (done,total) ->
                        progress(if(done>=total) "Verifying offline catalog…" else "Extracting offline catalog · ${done*100/total}%")
                    } }
                    try { when(val r=generations.stage(manifest,compressed.absolutePath)) { is Outcome.Failure -> error(r.error.message);is Outcome.Success -> Unit } }
                    finally { observer.cancelAndJoin() }
                }
                when(val r=generations.activate(manifest.generationId)) { is Outcome.Failure -> error(r.error.message);is Outcome.Success -> Unit }
            } finally { compressed.delete() }
        }
        val catalog=CatalogStorage(generations,personal)
        val resolver=CatalogSourceResolver()
        val playback=tech.imitravanu.eroflix.core.media.ProductionPlayback(context.applicationContext,catalog,personal,resolver)
        val app=context.applicationContext as EroflixApplication
        withContext(Dispatchers.Main.immediate) { app.playback=playback;app.privacy.sync() }
        val downloads=tech.imitravanu.eroflix.core.media.ProductionDownloads.create(context.applicationContext,personal,resolver,playback)
        app.downloadMutable.value=downloads
        val exports=tech.imitravanu.eroflix.core.media.ProductionExports.create(context.applicationContext,personal,downloads)
        app.exportMutable.value=exports
        return AppContainer(catalog,personal,resolver,playback,downloads,exports,generations,PendingUpdates,BuildConfig.CATALOG_FIXTURE)
    } catch(e: Exception) { personal.close();throw e }
}
private fun pending()=Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"This feature is being built in a later phase."))
private object PendingMedia : PlayerControl,DownloadController,ExportEngine {
    override val state=MutableStateFlow(PlayerState())
    override val jobs=MutableStateFlow<List<DownloadJob>>(emptyList())
    override val exports=MutableStateFlow<List<ExportJob>>(emptyList())
    override suspend fun open(source: SourceHandle,startPositionMs: Long)=pending()
    override fun pause() { state.value=state.value.copy(playing=false) }
    override fun seekTo(positionMs: Long) { require(positionMs>=0) }
    override fun setSpeed(multiplier: Float) { require(multiplier>0 && multiplier.isFinite()) }
    override fun stop() { state.value=PlayerState() }
    override suspend fun enqueue(intent: DownloadIntent)=pending()
    override suspend fun pause(id: JobId)=pending()
    override suspend fun resume(id: JobId)=pending()
    override suspend fun remove(id: JobId)=pending()
    override suspend fun openOffline(id: JobId)=pending()
    override suspend fun export(downloadId: JobId,destination: String)=pending()
    override suspend fun cancel(exportId: JobId)=pending()
}
private object PendingUpdates : CatalogUpdater {
    override val state=MutableStateFlow<UpdateState>(UpdateState.Idle)
    override suspend fun check(): Outcome<CatalogManifest?> {
        val error=AppError(ErrorCode.UNSUPPORTED,"Updates are not configured for this build.")
        state.value=UpdateState.Failed(error)
        return Outcome.Failure(error)
    }
    override suspend fun install(manifest: CatalogManifest)=pending()
    override fun cancel() { state.value=UpdateState.Idle }
}
