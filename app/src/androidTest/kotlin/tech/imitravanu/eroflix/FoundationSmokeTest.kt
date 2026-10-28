package tech.imitravanu.eroflix

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.security.NetworkSecurityPolicy
import android.view.WindowManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun fixtureOnly() { Assume.assumeTrue(BuildConfig.CATALOG_FIXTURE); awaitTitle("fixture:aurora") }
    private fun awaitTitle(id: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("title-$id").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun navigateRotateRecreateAndRestoreSearch() {
        awaitTitle("fixture:aurora")
        ui.onNodeWithTag("catalog-search").performTextInput("Aurora")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("title-fixture:river").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("title-fixture:aurora").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Fixture Archive").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Fixture Archive").performClick()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithTag("tab-3").performClick()
        ui.onNodeWithText("Device diagnostics").assertHasClickAction()
        ui.onNodeWithTag("tab-0").performClick()
        ui.onNodeWithTag("catalog-search").assertTextContains("Aurora")
        val original = ui.activity.requestedOrientation
        try {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) {
                var landscape = false
                ui.activityRule.scenario.onActivity {
                    landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
                landscape
            }
            ui.waitForIdle()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("catalog-search").assertTextContains("Aurora")
            awaitTitle("fixture:aurora")
        } finally {
            ui.activityRule.scenario.onActivity { it.requestedOrientation = original }
        }
    }
    @Test fun platformIdentitySecureWindowAndFixtureNetworkPolicy() {
        assertEquals(37, Build.VERSION.SDK_INT)
        assertEquals("tech.imitravanu.eroflix.debug", ui.activity.packageName)
        assertEquals(37, ui.activity.applicationInfo.minSdkVersion)
        assertEquals(37, ui.activity.applicationInfo.targetSdkVersion)
        ui.runOnIdle { assertTrue(ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        ui.onNodeWithText("PREVIEW · TEST CATALOG").assertExists()
        ui.onNodeWithText("Providers").assertHasClickAction()
        ui.onNodeWithText("Filters").assertHasClickAction()
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
    }
    @Test fun deviceDiagnosticsScreenLoadsAndRecordsMeasuredCapabilities() {
        awaitTitle("fixture:aurora")
        ui.onNodeWithTag("tab-3").performClick()
        ui.onNodeWithText("Device diagnostics").performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("Video decoders").fetchSemanticsNodes().isNotEmpty()
        }
        // Same reader used by the diagnostics screen, on the instrumentation thread.
        val diagnostics = readDeviceDiagnostics(ui.activity.applicationContext)
        assertEquals(37, diagnostics.api)
        assertTrue(diagnostics.pageSizeBytes > 0)
        assertTrue(diagnostics.availableStorageBytes > 0)
        assertTrue(diagnostics.videoDecoders.isNotEmpty())
        val measured = JSONObject()
            .put("model", diagnostics.model)
            .put("android_release", diagnostics.androidRelease)
            .put("api", diagnostics.api)
            .put("page_size_bytes", diagnostics.pageSizeBytes)
            .put("available_storage_bytes", diagnostics.availableStorageBytes)
            .put("biometric_status", diagnostics.biometricStatus)
            .put("video_decoders", JSONArray(diagnostics.videoDecoders))
        Log.i("EroflixP01", "SUMMARY " + JSONObject(measured.toString()).apply { remove("video_decoders") }.toString())
        diagnostics.videoDecoders.forEach { Log.i("EroflixP01", "DECODER $it") }
    }
    @Test fun bundledSQLiteLoadsAndProvidesFts5() {
        BundledSQLiteDriver().open(":memory:").use { db ->
            db.prepare("CREATE VIRTUAL TABLE proof USING fts5(title)").use { it.step() }
            db.prepare("INSERT INTO proof(title) VALUES ('Neutral Aurora Fixture')").use { it.step() }
            db.prepare("SELECT count(*) FROM proof WHERE proof MATCH 'Aurora'").use {
                assertTrue(it.step()); assertEquals(1L, it.getLong(0))
            }
        }
    }
}
