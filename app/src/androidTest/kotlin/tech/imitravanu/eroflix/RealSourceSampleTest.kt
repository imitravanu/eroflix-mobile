package tech.imitravanu.eroflix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry

/** Explicit real-provider experiment; unsupported cases stay in the fixed denominator. */
@RunWith(AndroidJUnit4::class)
class RealSourceSampleTest {
 @get:Rule val activity=ActivityScenarioRule(SourceLabActivity::class.java)
 @Test fun recordAllFixedSampleOutcomes()=runBlocking {
  lateinit var result: Deferred<JSONObject>
  val family=InstrumentationRegistry.getArguments().getString("sourceFamily")
  activity.scenario.onActivity { result=it.runRealSampleSuite(family) {
   withContext(Dispatchers.IO) {
    val instrumentation=InstrumentationRegistry.getInstrumentation();val uid=instrumentation.targetContext.applicationInfo.uid
    val descriptor=instrumentation.uiAutomation.executeShellCommand("dumpsys audio")
    val dump=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader -> reader.readText() }
    val active=dump.lineSequence().count { line -> "AudioPlaybackConfiguration piid:" in line && "u/pid:$uid/" in line && "state:started" in line }
    JSONObject().put("activeOwnAudio",active)
   }
  } }
  val report=withTimeout(2_400_000) { result.await() }
  val expected=if(family==null) 45 else 5
  assertEquals(expected,report.getJSONArray("cases").length())
  if(family!=null) for(index in 0 until expected) assertEquals(family,report.getJSONArray("cases").getJSONObject(index).getString("family"))
  // Passing means evidence collection completed, not that any provider works.
  assertEquals(expected,report.getInt("attempted"))
 }
}
