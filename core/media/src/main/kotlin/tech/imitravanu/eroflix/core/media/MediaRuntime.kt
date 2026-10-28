package tech.imitravanu.eroflix.core.media
import androidx.media3.common.MediaLibraryInfo
/** Player/service/cache ownership is introduced in P05/P06; no player is created by UI. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun packagedMediaVersion(): String = MediaLibraryInfo.VERSION
