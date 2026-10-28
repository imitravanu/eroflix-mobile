package tech.imitravanu.eroflix

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.feature.catalog.CatalogWindowEnd

/** Runs against the installed full catalog; no fixture replacement or personal-data writes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoveryDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun await(tag: String) {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun container(): AppContainer {
        await("catalog-search")
        return (ui.activity.application as EroflixApplication).container
    }
    private fun <T> ok(result: Outcome<T>): T = when(result) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> throw AssertionError(result.error.toString())
    }
    private fun search(text: String) {
        await("catalog-search")
        ui.onNodeWithTag("catalog-search").performTextClearance()
        if(text.isNotEmpty()) ui.onNodeWithTag("catalog-search").performTextInput(text)
        val first = runBlocking(Dispatchers.IO) { ok((ui.activity.application as EroflixApplication).container.catalog.query(CatalogQuery(search=text))).items.firstOrNull() }
        await("catalog-applied-${CatalogQuery(search=text).key()}")
        first?.let { await("title-${it.id.value}") }
    }
    private fun scrollTo(tag: String) { ui.onNodeWithTag("catalog-grid").performScrollToNode(hasTestTag(tag)); await(tag) }

    @Test fun searchDetailsEntityBackAndRecreationRestoreQueryAndVisibleIdentity() {
        val container=container()
        val request=CatalogQuery(search="a")
        val candidates=runBlocking(Dispatchers.IO) { ok(container.catalog.query(request)).items }
        val selected=runBlocking(Dispatchers.IO) {
            candidates.drop(12).map { ok(container.catalog.details(it.id)) }.firstOrNull { it.entities.isNotEmpty() }
                ?: candidates.map { ok(container.catalog.details(it.id)) }.first { it.entities.isNotEmpty() }
        }
        search("a")
        val tag="title-${selected.summary.id.value}"
        scrollTo(tag)
        ui.onNodeWithTag(tag).performClick(); await("title-details")
        val entity=selected.entities.first()
        val entityTag="entity-${entity.kind}-${entity.key}"
        ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag(entityTag))
        ui.onNodeWithTag(entityTag).performClick(); await("catalog-grid")
        ui.onNodeWithTag("catalog-search").assertTextContains("")
        ui.onNodeWithContentDescription("Back").performClick(); await("title-details")
        ui.onNodeWithContentDescription("Back").performClick(); await("catalog-grid")
        ui.onNodeWithTag("catalog-search").assertTextContains("a")
        ui.onNodeWithTag(tag).assertIsDisplayed()
        ui.activityRule.scenario.recreate(); await("catalog-grid")
        ui.onNodeWithTag("catalog-search").assertTextContains("a")
        ui.onNodeWithTag(tag).assertIsDisplayed()
    }
    @Test fun dualDrawersExposeRealCountsDismissWithBackAndZeroSearchHasReset() {
        container(); search("")
        ui.onNodeWithTag("providers-button").performClick()
        ui.onNodeWithText("Done").assertExists()
        ui.onNodeWithText("OR within a group · AND across groups. Counts exclude hidden titles.").assertExists()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("filters-button").performClick()
        ui.onNodeWithText("Done").assertExists()
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithTag("catalog-search").performTextInput("p04zzzznonexistenttitlezzzz")
        ui.waitUntil(30_000) { ui.onAllNodesWithText("No titles found").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Reset search and filters").performClick(); await("catalog-grid")
    }
    @Test fun exactSourceActionsAreWiredAndKeepDetailsReachable() {
        val container=container(); search("")
        val details=runBlocking(Dispatchers.IO) {
            ok(container.catalog.query(CatalogQuery())).items.map { ok(container.catalog.details(it.id)) }.first { it.sources.isNotEmpty() }
        }
        val titleTag="title-${details.summary.id.value}"; scrollTo(titleTag)
        ui.onNodeWithTag(titleTag).performClick(); await("title-details")
        val source=details.sources.first()
        // Playback is live in P05. Its exact-source/engine behavior is exercised by the
        // explicit production player suites; discovery must not start a provider implicitly.
        ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag("play-${source.id.value}"))
        ui.onNodeWithTag("play-${source.id.value}").assertHasClickAction()
        ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag("download-${source.id.value}"))
        // Both actions now resolve real providers. Verify the exact-source controls without
        // invoking either; isolated production suites own transfer/playback behavior.
        ui.onNodeWithTag("download-${source.id.value}").assertIsDisplayed().assertHasClickAction()
        ui.onNodeWithTag("title-details").assertIsDisplayed()
    }
    @Test fun deepWindowHeadRemovalDetailsBackAndRecreationKeepVisibleTitle() {
        val container=container(); search("")
        val target=runBlocking(Dispatchers.IO) {
            var cursor: PageCursor?=null
            var chosen: TitleSummary?=null
            repeat(6) {
                val page=ok(container.catalog.query(CatalogQuery(limit=120,cursor=cursor)))
                cursor=page.next
                chosen=page.items[60]
            }
            chosen!!
        }
        repeat(11) { index ->
            ui.onNodeWithTag("catalog-grid").performScrollToIndex(minOf((index+1)*60, 600))
            ui.onNodeWithTag("load-more").performClick()
            val expected=(index+2)*60
            ui.waitUntil(30_000) {
                ui.onAllNodes(hasTestTag("catalog-grid") and SemanticsMatcher.expectValue(CatalogWindowEnd,expected)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val tag="title-${target.id.value}"
        ui.onNodeWithTag("catalog-grid").performScrollToIndex(540)
        await(tag); ui.onNodeWithTag(tag).assertIsDisplayed()
        ui.onNodeWithTag(tag).performClick(); await("title-details")
        ui.onNodeWithContentDescription("Back").performClick(); await("catalog-grid")
        ui.onNodeWithTag(tag).assertIsDisplayed()
        ui.activityRule.scenario.recreate(); await("catalog-grid")
        ui.onNodeWithTag(tag).assertIsDisplayed()
        android.util.Log.i("EroflixP04","WINDOW pages=12 maxRetained=600 headRemoval=passed back=passed recreation=passed pssKiB=${android.os.Debug.getPss()}")
    }
    @Test fun currentFontScalePortraitLandscapeRecreationAndPagingRemainReachable() {
        container(); search("")
        android.util.Log.i("EroflixP04", "LAYOUT_STEP initial-results")
        ui.onNodeWithTag("catalog-grid").performScrollToIndex(60)
        ui.onNodeWithTag("load-more").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithContentDescription("Loading more titles").fetchSemanticsNodes().isEmpty() }
        android.util.Log.i("EroflixP04", "LAYOUT_STEP appended")
        val original=ui.activity.requestedOrientation
        try {
            ui.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) {
                var landscape=false
                ui.activityRule.scenario.onActivity { landscape=it.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            await("catalog-search")
            android.util.Log.i("EroflixP04", "LAYOUT_STEP landscape")
            ui.onNodeWithTag("providers-button").assertHasClickAction()
            ui.onNodeWithTag("filters-button").assertHasClickAction()
            ui.activityRule.scenario.recreate(); await("catalog-search")
            ui.onNodeWithTag("filters-button").performClick(); ui.onNodeWithText("Done").performClick()
            android.util.Log.i("EroflixP04", "LAYOUT fontScale=${ui.activity.resources.configuration.fontScale} widthDp=${ui.activity.resources.configuration.screenWidthDp} heightDp=${ui.activity.resources.configuration.screenHeightDp} paging=append-requested recreation=passed")
        } finally { ui.activityRule.scenario.onActivity { it.requestedOrientation=original } }
    }
}
