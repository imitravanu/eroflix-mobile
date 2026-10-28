package tech.imitravanu.eroflix

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tech.imitravanu.eroflix.core.media.*
import tech.imitravanu.eroflix.core.ui.EroflixTheme
import tech.imitravanu.eroflix.feature.player.PlayerScreen
import tech.imitravanu.eroflix.model.*

/** Instrumentation-owned neutral context only; never initializes another engine. */
internal object ProductionHarness {
    var owner: ProductionPlayback?=null
    var catalog: CatalogRepository?=null
}
class ProductionPlayerHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val owner=ProductionHarness.owner ?: return finish()
        val catalog=ProductionHarness.catalog ?: return finish()
        setContent { EroflixTheme { PlayerScreen(owner,catalog,
            onDownload={ Outcome.Failure(AppError(ErrorCode.UNSUPPORTED,"Neutral harness download is disabled.")) },
            onBack={ finish() },onPip={ enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build()) }) } }
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean,config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip,config)
        ProductionHarness.owner?.visibility(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),inPip)
    }
    override fun onStart() { super.onStart();ProductionHarness.owner?.visibility(true,isInPictureInPictureMode) }
    override fun onStop() { super.onStop();ProductionHarness.owner?.visibility(false,isInPictureInPictureMode) }
}
