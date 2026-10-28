package tech.imitravanu.eroflix

/** In-memory admission policy. A process restart always starts an enrolled lock closed. */
internal class PrivacySession(private val enabled: Boolean) {
    var locked: Boolean = enabled
        private set
    private var visible = false
    private var pip = false
    private var authenticationPrompt = false

    fun authenticated() { if (enabled) locked = false }
    fun authenticationStarted() { authenticationPrompt = true }
    fun authenticationEnded() { authenticationPrompt = false; if (!visible && !pip) lock() }
    fun visibilityChanged(appVisible: Boolean, inPictureInPicture: Boolean) {
        visible=appVisible; pip=inPictureInPicture
        if (!visible && !pip && !authenticationPrompt) lock()
    }
    fun screenOff() { lock() }
    fun lock() { if(enabled) locked=true }
    fun mayRevealProtectedContent(): Boolean = !locked
}
