package tech.imitravanu.eroflix

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.model.*

/** Focused physical input/semantics checks; does not claim spoken TalkBack perception. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoveryAccessibilityDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun await(tag: String) {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun readySearch(text: String) {
        await("catalog-search")
        ui.onNodeWithTag("catalog-search").performTextReplacement(text)
        await("catalog-applied-${CatalogQuery(search = text).key()}")
        ui.runOnIdle {
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        ui.waitForIdle()
    }
    private fun dismissPanel() {
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty() }
    }
    private fun edgeBack(cancel: Boolean) {
        val size = ui.activity.windowManager.currentWindowMetrics.bounds
        val start = SystemClock.uptimeMillis()
        val y = size.height() * .48f
        fun event(action: Int, x: Float) {
            val value = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            value.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(value, true)) } finally { value.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 1f)
        repeat(12) { step ->
            SystemClock.sleep(15)
            event(MotionEvent.ACTION_MOVE, 1f + size.width() * .45f * (step + 1) / 12)
        }
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, size.width() * .45f)
        ui.waitForIdle()
    }

    @Test fun accessibleLabelsTouchTargetsKeyboardAndHoldHandlesWork() {
        readySearch("")
        for (tag in listOf("providers-button", "filters-button")) {
            ui.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        val title = ui.onAllNodes(SemanticsMatcher("Labeled title card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("title-") == true &&
                !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
        }).onFirst()
        title.assertIsDisplayed().assertHasClickAction()
        val handles = ui.onAllNodesWithContentDescription("Providers handle. Tap or hold to open.")
        if (handles.fetchSemanticsNodes().isNotEmpty()) {
            handles.onFirst().assertHeightIsAtLeast(48.dp).performTouchInput { longClick() }
        } else {
            // Short windows deliberately expose hold on the permanent visible button.
            ui.onNodeWithTag("providers-button").performTouchInput { longClick() }
        }
        ui.onNodeWithText("Done").assertIsDisplayed()
        dismissPanel()
        ui.onNodeWithTag("catalog-search").performClick().performTextReplacement("classic")
        await("catalog-applied-${CatalogQuery(search = "classic").key()}")
        ui.onNodeWithTag("providers-button").assertIsDisplayed().performClick()
        ui.onNodeWithText("Done").assertIsDisplayed()
        dismissPanel()
        ui.onNodeWithTag("catalog-search").assertTextContains("classic")
        android.util.Log.i("EroflixP04", "ACCESS labels=passed targets48dp=passed hold=passed keyboard=passed")
    }

    @Test fun backCancellationAndCommitPreserveDiscoveryContext() {
        readySearch("classic")
        val container = (ui.activity.application as EroflixApplication).container
        val first = runBlocking(Dispatchers.IO) {
            (container.catalog.query(CatalogQuery(search = "classic")) as Outcome.Success).value.items.first()
        }
        ui.onNodeWithTag("title-${first.id.value}").performClick()
        await("title-details")
        val mode = InstrumentationRegistry.getArguments().getString("navigationMode") ?: "current"
        if (mode == "gesture") {
            edgeBack(cancel = true)
            ui.onNodeWithTag("title-details").assertExists()
            edgeBack(cancel = false)
        } else {
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        }
        await("catalog-grid")
        ui.onNodeWithTag("catalog-search").assertTextContains("classic")
        ui.onNodeWithTag("title-${first.id.value}").assertIsDisplayed()
        android.util.Log.i("EroflixP04", "BACK mode=$mode cancellation=${if(mode == "gesture") "passed" else "not-exercised"} commit=passed context=passed")
    }

    @Test fun homeAndReturnKeepDiscoveryQuery() {
        readySearch("classic")
        val component = "${ui.activity.packageName}/tech.imitravanu.eroflix.MainActivity"
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        SystemClock.sleep(500)
        // Match a launcher return to the existing task rather than starting a new standard Activity.
        val descriptor = automation.executeShellCommand("am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $component")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertFalse(output.contains("Error:"))
        await("catalog-applied-${CatalogQuery(search = "classic").key()}")
        ui.onNodeWithTag("catalog-search").assertTextContains("classic")
        ui.onNodeWithTag("providers-button").assertIsDisplayed()
        android.util.Log.i("EroflixP04", "HOME return=passed query=passed")
    }
}
