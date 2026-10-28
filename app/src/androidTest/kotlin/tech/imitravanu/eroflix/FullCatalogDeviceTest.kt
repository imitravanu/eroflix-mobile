package tech.imitravanu.eroflix

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.model.*

@RunWith(AndroidJUnit4::class)
class FullCatalogDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> ok(r: Outcome<T>): T=when(r) { is Outcome.Success -> r.value;is Outcome.Failure -> throw AssertionError(r.error.toString()) }
    @Test fun fullCatalogProvisionsOfflineAndMeasuresQueries()=runBlocking {
        Assume.assumeFalse(BuildConfig.CATALOG_FIXTURE)
        val app=ui.activity.application as EroflixApplication
        val started=SystemClock.elapsedRealtime()
        val ready=withTimeout(180_000) { app.provision.first { it is ProvisionState.Ready || it is ProvisionState.Failed } }
        assertTrue("Catalog provisioning failed: $ready",ready is ProvisionState.Ready)
        val container=(ready as ProvisionState.Ready).container
        ui.waitUntil(15_000) { runCatching { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val expected=ok(container.catalog.query(CatalogQuery(search="classic"))).items.first()
        ui.onNodeWithTag("catalog-search").performTextInput("classic")
        ui.waitUntil(15_000) {
            runCatching { ui.onAllNodesWithTag("title-${expected.id.value}").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        ui.onNodeWithTag("catalog-search").assertTextContains("classic")
        val provisionWaitMs=SystemClock.elapsedRealtime()-started
        val cases=listOf(CatalogQuery(),CatalogQuery(search="classic"),CatalogQuery(search="1984"),CatalogQuery(search="zznoresultzz"),CatalogQuery(search="東京"),
            CatalogQuery(providers=setOf(FacetValue.Known("eporner"))),CatalogQuery(countries=setOf(FacetValue.Unknown)),
            CatalogQuery(decades=setOf(FacetValue.Known("1980"))),CatalogQuery(providers=setOf(FacetValue.Known("eporner")),countries=setOf(FacetValue.Known("usa")),decades=setOf(FacetValue.Known("1980"))),CatalogQuery(search="cafe"))
        val times=mutableListOf<Double>();val facets=mutableListOf<Double>();val details=mutableListOf<Double>();val entities=mutableListOf<Double>()
        for(q in cases.take(3)) ok(container.catalog.query(q))
        repeat(3) { for(q in cases) {
            var start=SystemClock.elapsedRealtimeNanos();val page=ok(container.catalog.query(q));times+=(SystemClock.elapsedRealtimeNanos()-start)/1e6
            start=SystemClock.elapsedRealtimeNanos();ok(container.catalog.facets(q));val facetMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6;facets+=facetMs
            Log.i("EroflixP02","FACET_CASE ${cases.indexOf(q)} $facetMs")
            page.items.firstOrNull()?.let { item ->
                start=SystemClock.elapsedRealtimeNanos();val d=ok(container.catalog.details(item.id));details+=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                d.entities.firstOrNull()?.let { entity -> start=SystemClock.elapsedRealtimeNanos();ok(container.catalog.query(CatalogQuery(entity=entity)));entities+=(SystemClock.elapsedRealtimeNanos()-start)/1e6 }
            }
        } }
        fun stats(v: List<Double>): JSONObject { val s=v.sorted();return JSONObject().put("n",s.size).put("p50_ms",s[s.size/2]).put("p95_ms",s[(kotlin.math.ceil(s.size*.95).toInt()-1).coerceAtLeast(0)]).put("max_ms",s.last()) }
        val all=ok(container.catalog.facets(CatalogQuery()));assertEquals(286207L,all.providers.sumOf { it.titleCount })
        val report=JSONObject().put("generation",container.catalog.activeGeneration.value?.value).put("title_count",286207).put("provision_wait_ms",provisionWaitMs)
            .put("query",stats(times)).put("facets",stats(facets)).put("details",stats(details)).put("entity",stats(entities)).put("pss_kib",Debug.getPss())
        Log.i("EroflixP02","BENCHMARK $report")
        assertTrue("Query p95 budget missed",stats(times).getDouble("p95_ms")<=250)
        assertTrue("Facet p95 budget missed",stats(facets).getDouble("p95_ms")<=500)
        assertTrue("Details p95 budget missed",stats(details).getDouble("p95_ms")<=250)
        assertTrue("Entity p95 budget missed",stats(entities).getDouble("p95_ms")<=250)
        assertTrue("Browse memory budget missed",report.getInt("pss_kib")<=250*1024)
    }
}
