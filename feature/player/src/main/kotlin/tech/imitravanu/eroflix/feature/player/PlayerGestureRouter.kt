package tech.imitravanu.eroflix.feature.player

import android.app.Activity
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import tech.imitravanu.eroflix.core.media.ProductionPlayback
import kotlin.math.*

/** One in-content pointer owner. Two fingers exclusively pinch until the stream ends. */
internal class PlayerGestureRouter(private val activity: Activity,private val owner: ProductionPlayback,
    private val locked: ()->Boolean,private val crop: (Boolean)->Unit,private val disallow: (Boolean)->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val audio=activity.getSystemService(AudioManager::class.java)
    private val slop=ViewConfiguration.get(activity).scaledTouchSlop
    private var active=false
    private var moved=false
    private var pinch=false
    private var preview=false
    private var axis=0 // 0 ignored/tap, 1 left brightness, 2 right volume/gain, 3 center scrub
    private var downX=0f;private var downY=0f
    private var width=1f;private var height=1f
    private var span=0f
    private var speed=1f
    private var brightness=-1f
    private var volume=0
    private var gain=1f
    private var position=0L
    private var downTime=0L
    private var lastTap=0L
    private var lastTapX=0f;private var lastTapY=0f
    private val hold=Runnable {
        if(active && !moved && !pinch && !locked()) { preview=true;owner.previewSpeed(2f) }
    }
    private fun restorePreview() {
        handler.removeCallbacks(hold)
        if(preview) owner.previewSpeed(speed)
        preview=false
    }
    fun cancel() {
        if(active && axis==1) activity.window.attributes=activity.window.attributes.apply { screenBrightness=brightness }
        restorePreview();active=false;disallow(false)
    }
    fun touch(event: MotionEvent,viewWidth: Float,viewHeight: Float): Boolean {
        if(locked()) { cancel();return true }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel();active=true;moved=false;pinch=false;axis=0
                width=viewWidth.coerceAtLeast(1f);height=viewHeight.coerceAtLeast(1f)
                downX=event.x;downY=event.y;downTime=event.eventTime
                speed=owner.ui.value.speed;brightness=activity.window.attributes.screenBrightness
                volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);gain=owner.ui.value.gain;position=owner.ui.value.positionMs
                disallow(true);handler.postDelayed(hold,450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                when(axis) {
                    1 -> activity.window.attributes=activity.window.attributes.apply { screenBrightness=brightness }
                    2 -> { audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);owner.gain(gain) }
                    3 -> owner.seekTo(position)
                }
                axis=0;restorePreview();moved=true;pinch=true
                if(event.pointerCount>=2) span=hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0)).coerceAtLeast(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if(!active) return false
                if(event.pointerCount>=2) {
                    restorePreview();moved=true;pinch=true
                    val current=hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0))
                    if(span>0) { if(current/span>1.08f) crop(true) else if(current/span<.92f) crop(false) }
                } else if(!pinch) {
                    val dx=event.x-downX;val dy=event.y-downY
                    if(!moved && hypot(dx,dy)>slop) {
                        moved=true;restorePreview()
                        axis=when {
                            downX<width*.3f && abs(dy)>abs(dx) -> 1
                            downX>width*.7f && abs(dy)>abs(dx) -> 2
                            downX in width*.3f..width*.7f && abs(dx)>abs(dy) -> 3
                            else -> 0
                        }
                    }
                    when(axis) {
                        1 -> activity.window.attributes=activity.window.attributes.apply { screenBrightness=((brightness.takeIf { it>=0 } ?: .5f)-dy/height).coerceIn(.02f,1f) }
                        2 -> {
                            val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val level=(volume.toFloat()/max+(gain-1f)-dy/height).coerceIn(0f,2f)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC,(minOf(1f,level)*max).roundToInt(),0)
                            owner.gain(if(level>1f) level else 1f)
                        }
                        3 -> owner.ui.value.durationMs?.let { owner.seekTo((position+dx/width*it).toLong().coerceIn(0,it)) }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { restorePreview();pinch=true }
            MotionEvent.ACTION_UP -> {
                if(active && !moved && !pinch && !preview && event.eventTime-downTime<400) {
                    if(lastTap>0 && event.eventTime-lastTap<350 && hypot(event.x-lastTapX,event.y-lastTapY)<slop*4) {
                        owner.seekTo(owner.ui.value.positionMs+if(event.x<width/2) -10_000 else 10_000);lastTap=0
                    } else { lastTap=event.eventTime;lastTapX=event.x;lastTapY=event.y }
                }
                restorePreview();active=false;disallow(false)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
}
