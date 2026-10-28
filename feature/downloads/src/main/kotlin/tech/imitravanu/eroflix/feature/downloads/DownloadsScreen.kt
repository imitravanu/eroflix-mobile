package tech.imitravanu.eroflix.feature.downloads

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import kotlinx.coroutines.launch
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.core.ui.MessageState
import tech.imitravanu.eroflix.model.*

@Composable fun DownloadsScreen(controller: ProductionDownloads,exports: ExportEngine,onOffline: (DownloadJob)->Unit) {
    val jobs by controller.jobs.collectAsStateWithLifecycle()
    val exportJobs by exports.exports.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var requestedExport by remember { mutableStateOf<JobId?>(null) }
    val exportScope=rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val document=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val id=requestedExport;requestedExport=null
        if(uri!=null && id!=null) exportScope.launch { exportMessage=when(val result=exports.export(id,uri.toString())) {
            is Outcome.Failure -> result.error.message;is Outcome.Success -> "Export queued. Original download is retained."
        } }
    }
    val wifi by controller.wifiOnly.collectAsStateWithLifecycle()
    val parallel by controller.parallel.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var actionMessage by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend ()->Outcome<*>) { scope.launch { actionMessage=when(val result=block()) { is Outcome.Failure -> result.error.message;is Outcome.Success -> "Updated." } } }
    LazyColumn(Modifier.fillMaxSize().testTag("downloads-queue"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads",style=MaterialTheme.typography.headlineMedium) }
        item { Row(Modifier.fillMaxWidth().clickable { controller.setWifiOnly(!wifi) }.heightIn(min=48.dp)) { Checkbox(checked=wifi,onCheckedChange=null);Text("Wi-Fi only",Modifier.padding(12.dp)) } }
        item { Text("Wi-Fi-only requires the active network to expose actual Wi-Fi transport. A VPN with unknown underlying transport waits; unmetered cellular is not treated as Wi-Fi.",style=MaterialTheme.typography.bodySmall) }
        item { Text("Active transfers: $parallel (one while streaming)");Slider(value=parallel.toFloat(),onValueChange={ controller.setParallel(it.toInt()) },valueRange=1f..4f,steps=2,modifier=Modifier.semantics { contentDescription="Concurrent downloads" }) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ scope.launch { controller.pauseAll() } }) { Text("Pause all") }
            OutlinedButton(onClick={ scope.launch { controller.resumeAll() } }) { Text("Resume all") }
        } }
        message?.let { item { Text(it,Modifier.semantics { liveRegion=LiveRegionMode.Polite }) } }
        actionMessage?.let { item { Text(it,Modifier.semantics { liveRegion=LiveRegionMode.Polite }) } }
        item { Text("Export creates a standalone H264/AAC MP4 through validated internal staging. Video/audio only; unsupported codecs, encrypted media, subtitle-selected downloads or non-readable/non-seekable destinations fail with the original download retained.",style=MaterialTheme.typography.bodySmall) }
        exportMessage?.let { item { Text(it,Modifier.semantics { liveRegion=LiveRegionMode.Polite }) } }
        if(jobs.isEmpty()) item { MessageState("No downloads", "Choose a source from title details or the player. Actual video/audio tracks are confirmed before queuing.") }
        items(jobs,key={ it.id.value }) { job -> Card(Modifier.fillMaxWidth().testTag("download-${job.id.value}")) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(job.intent.title.title,style=MaterialTheme.typography.titleMedium)
                Text(job.status.name.lowercase().replace('_',' '))
                Text("${bytes(job.downloadedBytes)} / ${job.totalBytes?.let(::bytes) ?: "size unknown"}")
                job.totalBytes?.takeIf { it>0 }?.let { total -> LinearProgressIndicator(progress={ (job.downloadedBytes.toDouble()/total).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth()) }
                job.error?.let { Text(it.message) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(job.status==DownloadStatus.COMPLETED) Button(onClick={ scope.launch { when(val result=controller.openOffline(job.id)) {
                        is Outcome.Success -> onOffline(job)
                        is Outcome.Failure -> actionMessage=result.error.message
                    } } }) { Text("Play offline") }
                    else {
                        OutlinedButton(onClick={ action { controller.pause(job.id) } }) { Text("Pause") }
                        OutlinedButton(onClick={ action { controller.resume(job.id) } }) { Text("Resume / retry") }
                    }
                    if(job.status==DownloadStatus.COMPLETED) OutlinedButton(onClick={
                        requestedExport=job.id
                        document.launch(job.intent.title.title.replace(Regex("[^A-Za-z0-9 _.-]"),"_").take(96).ifBlank { "Eroflix export" }+".mp4")
                    }) { Text("Export MP4") }
                    TextButton(onClick={ action { controller.remove(job.id) } }) { Text("Remove") }
                }
            }
        } }
        items(exportJobs,key={ "export-${it.id.value}" }) { job ->
            Card(Modifier.fillMaxWidth().testTag("export-${job.id.value}")) { Column(Modifier.padding(16.dp)) {
                Text("Export · ${job.status.name.lowercase()}")
                if(job.totalBytes!=null) Text("${bytes(job.bytesCopied)} / ${bytes(job.totalBytes!!)}")
                job.error?.let { Text(it.message) }
                if(job.status in listOf(ExportStatus.EXPORTING,ExportStatus.QUEUED)) TextButton(onClick={ action { exports.cancel(job.id) } }) { Text("Cancel export") }
                if(job.status==ExportStatus.EXPORTED) Text("Validated standalone MP4 saved to the chosen document. Removing the download does not remove this exported file.")
            } }
        }
    }
}
private fun bytes(value: Long)=if(value<1024) "$value B" else if(value<1024*1024) "${value/1024} KiB" else "${value/(1024*1024)} MiB"

/** App-owned single confirmation flow shared by details and player, never starts a playback probe. */
@Composable fun DownloadSelectionDialog(controller: ProductionDownloads) {
    val offer by controller.offer.collectAsStateWithLifecycle()
    val preparing by controller.preparing.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var pendingCommit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pendingCommit?.invoke();pendingCommit=null }
    var error by remember(offer?.token) { mutableStateOf<String?>(null) }
    var busy by remember(offer?.token) { mutableStateOf(false) }
    if(preparing) AlertDialog(onDismissRequest=controller::cancelOffer,title={ Text("Preparing download tracks") },text={ Column { CircularProgressIndicator();Text("Resolving this source for DOWNLOAD and reading its actual tracks. No player is created.") } },confirmButton={},dismissButton={ TextButton(onClick=controller::cancelOffer) { Text("Cancel") } })
    offer?.let { current ->
        val initial=remember(current.token) {
            current.options.filter { it.type in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO) }.groupBy { it.period to it.type }.values.map { choices ->
                choices.filter { it.type!=C.TRACK_TYPE_VIDEO || (it.height ?: 0)<=1080 }.maxByOrNull { it.height ?: 0 } ?: choices.minBy { it.height ?: 0 }
            }.toSet()
        }
        var chosen by remember(current.token) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest={ if(!busy) controller.cancelOffer() },title={ Text("Download ${current.intent.title.title}") },text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Confirm actual prepared tracks. Existing in-player format IDs are advisory; group/period identities here are authoritative.")
                if(current.progressive) Text("Source quality · progressive file. This source has no separate adaptive quality choices.")
                current.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=!busy) {
                        chosen=if(option in chosen) chosen-option else if(option.type==C.TRACK_TYPE_TEXT) chosen+option
                            else chosen.filterNot { it.period==option.period && it.type==option.type }.toSet()+option
                    },verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked=option in chosen,onCheckedChange=null)
                        Text("Period ${option.period+1} · ${option.label}",Modifier.padding(start=8.dp))
                    }
                }
                Text("1 GiB free-space floor plus existing reservations is required. Wi-Fi-only is on by default; queue policy is in Downloads.")
                if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) Text("Notification permission helps show transfer controls. If denied, Android foreground-task controls still apply.")
                error?.let { Text(it,Modifier.semantics { liveRegion=LiveRegionMode.Polite },color=MaterialTheme.colorScheme.error) }
            }
        },confirmButton={ Button(enabled=!busy,onClick={
            val commit={ busy=true;scope.launch {
                when(val result=controller.confirm(current.token,chosen.toList())) {
                    is Outcome.Failure -> { error=result.error.message;busy=false }
                    is Outcome.Success -> busy=false
                }
            };Unit }
            if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) { pendingCommit=commit;permission.launch(Manifest.permission.POST_NOTIFICATIONS) } else commit()
        }) { Text(if(busy) "Queuing…" else "Queue download") } },dismissButton={ TextButton(enabled=!busy,onClick=controller::cancelOffer) { Text("Cancel") } })
    }
}
