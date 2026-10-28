package tech.imitravanu.eroflix.core.media

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.ExportStatus

/** Eligible bounded media-processing foreground lifecycle; no background auto-retry. */
class ProductionExportService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var owner: ProductionExports?=null
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("exports","Media exports",NotificationManager.IMPORTANCE_LOW))
        startForeground(7201,notification("Preparing standalone media…"))
        scope.launch {
            val ready=withTimeoutOrNull(20_000) { (application as? ExportApplication)?.exportOwner?.filterNotNull()?.first() }
            if(ready==null) { stopSelf();return@launch }
            owner=ready;ready.foregroundStarted()
            ready.exports.collect { jobs ->
                val active=jobs.firstOrNull { it.status in listOf(ExportStatus.EXPORTING,ExportStatus.QUEUED) }
                if(active==null) stopSelf() else notifications.notify(7201,notification(if(active.bytesCopied>0) "Copying ${active.bytesCopied/(1024*1024)} MiB to your document…" else "Preparing and validating standalone MP4…"))
            }
        }
    }
    private fun notification(text: String)=Notification.Builder(this,"exports").setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Eroflix export").setContentText(text).setOnlyAlertOnce(true).setOngoing(true).build()
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int)=START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onTimeout(startId: Int,fgsType: Int) { scope.launch { owner?.timeout();stopSelf() } }
    override fun onDestroy() { owner?.foregroundStopped();scope.cancel();super.onDestroy() }
}
