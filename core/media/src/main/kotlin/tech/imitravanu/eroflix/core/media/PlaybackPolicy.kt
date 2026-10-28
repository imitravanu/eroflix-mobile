package tech.imitravanu.eroflix.core.media

import kotlin.math.abs

/** Resume requires both exact timeline identity and a compatible finite duration. */
fun compatibleTimeline(oldIdentity: String?, newIdentity: String?, oldDurationMs: Long?, newDurationMs: Long?): Boolean =
    oldIdentity != null && oldIdentity == newIdentity && oldDurationMs != null && newDurationMs != null &&
        oldDurationMs > 0 && newDurationMs > 0 && abs(oldDurationMs-newDurationMs) <= 1000

/** Never turn seeks, stalls, or restoration into played coverage. */
class AdvancingIntervals {
    private var position: Long? = null
    private var elapsed: Long? = null
    fun sample(nowMs: Long, positionMs: Long, playing: Boolean, speed: Float, discontinuity: Boolean): Long? {
        val oldPosition=position; val oldElapsed=elapsed
        position=positionMs; elapsed=nowMs
        if(discontinuity || !playing || oldPosition==null || oldElapsed==null) return null
        val delta=positionMs-oldPosition; val wall=nowMs-oldElapsed
        return oldPosition.takeIf { delta > 0 && wall in 1..6000 && delta <= wall*speed.coerceIn(.25f,3f)+500 }
    }
    fun reset() { position=null;elapsed=null }
}

/** Symmetric soft saturation keeps amplified PCM finite and within [-1,1]. */
fun gainedSample(sample: Float, gain: Float): Float {
    val safe=sample.coerceIn(-1f,1f); val factor=gain.coerceIn(1f,2f)
    return if(factor==1f) safe else kotlin.math.tanh((safe*factor).toDouble()).toFloat()
}

/** Main-thread admission token invalidated by route exit, background, stop or lock. */
class PlaybackAdmission {
    var locked=false; private set
    var foreground=true; private set
    var pip=false; private set
    var route=false; private set
    var backgroundAudio=false
    private var epoch=0L
    fun begin(): Long? {
        if(locked || !foreground) return null
        route=true
        return ++epoch
    }
    fun valid(token: Long)=token==epoch && !locked && route && (foreground || pip || backgroundAudio)
    fun visible(visible: Boolean,inPip: Boolean) {
        foreground=visible;pip=inPip
        if(!visible && !inPip) ++epoch // Even opt-in audio never authorizes late resolver starts.
    }
    fun restoreRoute() { route=true }
    fun leave() { route=false;++epoch }
    fun lock(value: Boolean) { locked=value;if(value) ++epoch }
    fun stop() { route=false;++epoch }
}
