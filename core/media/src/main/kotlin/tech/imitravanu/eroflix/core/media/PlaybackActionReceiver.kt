package tech.imitravanu.eroflix.core.media
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val owner=(context.applicationContext as? PlaybackApplication)?.playbackOrNull ?: return
        if(owner.ui.value.locked) { owner.pause();return }
        when(intent.action) {
            "toggle" -> if(owner.ui.value.phase==PlaybackPhase.PLAYING) owner.pause() else owner.play()
            "play" -> owner.play()
            "pause" -> owner.pause()
            "skip" -> owner.seekTo(owner.ui.value.positionMs+10_000)
        }
    }
}
