package tech.imitravanu.eroflix

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.imitravanu.eroflix.core.ui.EroflixTheme
import tech.imitravanu.eroflix.core.ui.MessageState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : FragmentActivity() {
    internal fun authenticateFingerprint(setup: Boolean,recover: Boolean=false) {
        showFingerprintPrompt(this,(application as EroflixApplication).privacy,setup,recover)
    }
    private val pendingSubtitle = mutableStateOf<android.net.Uri?>(null)
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pendingSubtitle.value = uri
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingSubtitle.value?.let { outState.putString("pending-subtitle-uri",it.toString()) }
        super.onSaveInstanceState(outState)
    }
    var configuredPipParams: android.app.PictureInPictureParams?=null
        private set
    private fun playback()=(application as EroflixApplication).takeIf { it.provision.value is ProvisionState.Ready }?.playback
    fun enterPlayerPip() {
        val owner=playback() ?: return
        if(!owner.canPip() || owner.ui.value.phase==tech.imitravanu.eroflix.core.media.PlaybackPhase.IDLE) return
        val playing=owner.ui.value.phase==tech.imitravanu.eroflix.core.media.PlaybackPhase.PLAYING
        fun action(name: String,label: String,icon: Int)=android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,icon),label,label,
            android.app.PendingIntent.getBroadcast(this,if(name=="skip") 2 else 1,android.content.Intent(this,tech.imitravanu.eroflix.core.media.PlaybackActionReceiver::class.java).setAction(name),android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        val size=owner.surfacePlayer.value?.videoSize
        val ratio=if(size!=null && size.width>0 && size.height>0) (size.width*size.pixelWidthHeightRatio/size.height).coerceIn(.42f,2.39f) else 16f/9f
        val params=android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((ratio*1000).toInt(),1000)).setAutoEnterEnabled(false)
            .setActions(listOf(action("toggle","Play or pause",if(playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),action("skip","Forward 10 seconds",android.R.drawable.ic_media_ff))).build()
        configuredPipParams=params
        if(isInPictureInPictureMode) setPictureInPictureParams(params) else enterPictureInPictureMode(params)
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val owner=playback() ?: return
        if(owner.canPip() && owner.ui.value.phase==tech.imitravanu.eroflix.core.media.PlaybackPhase.PLAYING && !owner.ui.value.backgroundAudio) enterPlayerPip()
    }
    override fun onStart() { super.onStart();(application as EroflixApplication).privacy.visible(true,isInPictureInPictureMode);playback()?.let { owner -> owner.visibility(true,isInPictureInPictureMode);owner.onLockUi={ if(isInPictureInPictureMode) finish() } } }
    override fun onDestroy() { playback()?.onLockUi=null;super.onDestroy() }
    override fun onStop() { super.onStop();if(!isChangingConfigurations) (application as EroflixApplication).privacy.visible(false,isInPictureInPictureMode);playback()?.visibility(false,isInPictureInPictureMode) }
    override fun onPictureInPictureModeChanged(inPip: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip,config)
        (application as EroflixApplication).privacy.visible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),inPip)
        playback()?.visibility(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),inPip)
        if(!inPip && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) playback()?.pause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSubtitle.value = savedInstanceState?.getString("pending-subtitle-uri")?.let(android.net.Uri::parse)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        NotificationChannels.create(this)
        val app = application as EroflixApplication
        setContent { EroflixTheme {
            val state by app.provision.collectAsStateWithLifecycle()
            val privacy by app.privacy.state.collectAsStateWithLifecycle()
            val shellState = rememberSaveableStateHolder()
            LaunchedEffect(state) { if(state is ProvisionState.Ready) { app.privacy.sync();playback()?.visibility(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),isInPictureInPictureMode) } }
            if(privacy.locked) PinUnlockScreen(app.privacy,privacy)
            else when(val current=state) {
                is ProvisionState.Working -> MessageState("Preparing offline catalog",current.message)
                is ProvisionState.Failed -> MessageState("Catalog unavailable",current.message,retry=app::prepare)
                is ProvisionState.Ready -> shellState.SaveableStateProvider("protected-shell") {
                    EroflixShell(appContainerForUi(current.container),
                        requestSubtitle={ subtitlePicker.launch(arrayOf("text/*","application/x-subrip")) },
                        pendingSubtitle=pendingSubtitle.value, onSubtitleConsumed={ pendingSubtitle.value=null })
                }
            }
        } }
    }
}
