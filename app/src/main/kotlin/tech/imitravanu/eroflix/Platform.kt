package tech.imitravanu.eroflix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import androidx.biometric.BiometricManager
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "eroflix_settings")
object NotificationChannels {
    const val PLAYBACK = "playback"
    const val DOWNLOADS = "downloads"
    const val EXPORTS = "exports"
    fun create(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(PLAYBACK, "Playback", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(EXPORTS, "Exports", NotificationManager.IMPORTANCE_LOW)))
    }
}
data class DeviceDiagnostics(val model: String, val androidRelease: String, val api: Int,
    val pageSizeBytes: Long, val availableStorageBytes: Long, val biometricStatus: String,
    val videoDecoders: List<String>)
/** Called on Dispatchers.IO only, after the user opens diagnostics. Never includes serial/IDs. */
fun readDeviceDiagnostics(context: Context): DeviceDiagnostics {
    val status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    val biometric = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Strong biometric authentication is available"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometric enrolled"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No compatible biometric hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware temporarily unavailable"
        else -> "Biometric status: $status"
    }
    val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        .flatMap { codec -> codec.supportedTypes.filter { it.startsWith("video/") }.map {
            "${codec.name} · $it · ${if (codec.isHardwareAccelerated) "hardware" else "software"}"
        } }.sorted()
    return DeviceDiagnostics(Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        Os.sysconf(OsConstants._SC_PAGESIZE), StatFs(context.filesDir.absolutePath).availableBytes, biometric, decoders)
}
