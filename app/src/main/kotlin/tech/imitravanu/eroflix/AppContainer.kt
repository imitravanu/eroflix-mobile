package tech.imitravanu.eroflix

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.*

data class AppContainer(val catalog: CatalogRepository,val personal: PersonalRepository,
    val resolver: SourceResolver,val player: PlayerControl,val downloads: DownloadController,
    val exports: ExportEngine,val generations: CatalogGenerationStore,val updater: CatalogUpdater,val fixture: Boolean)
sealed interface ProvisionState {
    data class Working(val message: String): ProvisionState
    data class Ready(val container: AppContainer): ProvisionState
    data class Failed(val message: String): ProvisionState
}
class EroflixApplication : Application(), tech.imitravanu.eroflix.core.media.PlaybackApplication, tech.imitravanu.eroflix.core.media.DownloadApplication, tech.imitravanu.eroflix.core.media.ExportApplication {
    val gridDensity=MutableStateFlow(150)
    fun setGridDensity(value: Int) {
        val selected=if(value in listOf(110,150,220)) value else 150
        gridDensity.value=selected
        getSharedPreferences("display-settings-v1",MODE_PRIVATE).edit().putInt("gridDensity",selected).apply()
    }
    internal lateinit var privacy: PrivacyController
        private set
    val exportMutable=MutableStateFlow<tech.imitravanu.eroflix.core.media.ProductionExports?>(null)
    override val exportOwner=exportMutable.asStateFlow()
    val downloadMutable=MutableStateFlow<tech.imitravanu.eroflix.core.media.ProductionDownloads?>(null)
    override val downloadOwner=downloadMutable.asStateFlow()
    lateinit var playback: tech.imitravanu.eroflix.core.media.ProductionPlayback
    override val playbackOrNull get()=if(::playback.isInitialized && playbackServiceReady()) playback else null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow<ProvisionState>(ProvisionState.Working("Preparing your catalog…"))
    val provision=mutable.asStateFlow()
    private var preparing: Job?=null
    val container: AppContainer get()=(mutable.value as ProvisionState.Ready).container
    override fun onCreate() {
        super.onCreate()
        val savedGrid=getSharedPreferences("display-settings-v1",MODE_PRIVATE).getInt("gridDensity",150)
        gridDensity.value=if(savedGrid in listOf(110,150,220)) savedGrid else 150
        privacy=PrivacyController(PinVault(this),BiometricVault(this)) { locked -> if(::playback.isInitialized) playback.lock(locked) }
        registerReceiver(object: android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if(intent?.action==android.content.Intent.ACTION_SCREEN_OFF) privacy.lock()
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), android.content.Context.RECEIVER_NOT_EXPORTED)
        prepare()
    }
    fun prepare() {
        if(preparing?.isActive==true) return
        preparing=scope.launch {
            val started=android.os.SystemClock.elapsedRealtime()
            try {
                mutable.value=ProvisionState.Ready(createAppContainer(this@EroflixApplication) { mutable.value=ProvisionState.Working(it) })
                if(BuildConfig.DEBUG) android.util.Log.i("EroflixP02","PROVISION_MS ${android.os.SystemClock.elapsedRealtime()-started}")
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.value=ProvisionState.Failed(e.message ?: "Catalog preparation failed. Your personal data is preserved.") }
        }
    }
}
