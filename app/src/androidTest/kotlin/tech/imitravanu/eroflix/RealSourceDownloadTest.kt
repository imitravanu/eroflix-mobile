package tech.imitravanu.eroflix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealSourceDownloadTest {
 @get:Rule val activity=ActivityScenarioRule(SourceLabActivity::class.java)
 @Test fun completeFixedProviderFileAndPlayWithoutUpstream()=runBlocking {
  lateinit var deferred: Deferred<JSONObject>
  activity.scenario.onActivity { deferred=it.runRealDownloadProof() }
  val report=withTimeout(660000) { deferred.await() }
  assertTrue(report.toString(),report.getBoolean("completed"))
  assertEquals(report.getLong("resolvedBytes"),report.getLong("downloadedBytes"))
  assertEquals(0L,report.getLong("upstreamBytesDuringOffline"))
  assertTrue(report.getJSONObject("cacheOnlyPlayback").getLong("positionMs")>=2000)
 }
}
