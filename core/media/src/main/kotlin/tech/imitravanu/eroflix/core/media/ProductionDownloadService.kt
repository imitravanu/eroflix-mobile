package tech.imitravanu.eroflix.core.media

import android.app.*
import android.app.job.*
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tech.imitravanu.eroflix.model.DownloadStatus

/** Bounded bootstrap avoids assuming catalog/application preparation completed on cold start. */
class ProductionDownloadService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var owner: ProductionDownloads?=null
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW))
        startForeground(6201,notification("Preparing durable downloads…"))
        scope.launch {
            val ready=withTimeoutOrNull(20_000) { (application as? DownloadApplication)?.downloadOwner?.filterNotNull()?.first() }
            if(ready==null) { stopSelf();return@launch }
            owner=ready;ready.foregroundStarted()
            ready.jobs.collect { jobs ->
                val active=jobs.count { it.status==DownloadStatus.DOWNLOADING }
                notifications.notify(6201,notification("$active active · ${jobs.count { it.status==DownloadStatus.QUEUED }} queued"))
                if(jobs.none { it.status in listOf(DownloadStatus.DOWNLOADING,DownloadStatus.QUEUED,DownloadStatus.RESOLVING) }) stopSelf()
            }
        }
    }
    private fun notification(text: String): Notification=Notification.Builder(this,"downloads")
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Eroflix downloads").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).build()
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int)=START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onTimeout(startId: Int,fgsType: Int) {
        scope.launch { owner?.systemPause("Android data-sync timeout. Open Downloads to resume.");stopSelf() }
    }
    override fun onDestroy() { owner?.foregroundStopped();scope.cancel();super.onDestroy() }
}

class DownloadRecoveryJob : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val owner=withTimeoutOrNull(15_000) { (application as? DownloadApplication)?.downloadOwner?.filterNotNull()?.first() }
            if(owner!=null) {
                try { startForegroundService(Intent(this@DownloadRecoveryJob,ProductionDownloadService::class.java)) }
                catch(_: RuntimeException) { owner.systemPause("Android requires a visible Resume action before download recovery.") }
            }
            jobFinished(params,owner==null)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { scope.coroutineContext.cancelChildren();return true }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
    companion object {
        fun schedule(context: Context) {
            val job=JobInfo.Builder(6202,ComponentName(context,DownloadRecoveryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true).setPersisted(true)
                .setMinimumLatency(30_000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
