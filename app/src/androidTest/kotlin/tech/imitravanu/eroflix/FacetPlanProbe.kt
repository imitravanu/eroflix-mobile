package tech.imitravanu.eroflix

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import android.os.SystemClock
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Diagnostic query-plan comparison; excluded from normal acceptance suites. */
@RunWith(AndroidJUnit4::class)
class FacetPlanProbe {
 @Test fun comparePlansAndTempStorage() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(context.filesDir,"catalog-generations")
  val id=JSONObject(File(root,"current.json").readText()).getString("current")
  BundledSQLiteDriver().open(File(context.filesDir,"personal/personal.db").absolutePath,1 or 64).use { c ->
   fun exec(sql: String) { c.prepare(sql).use { it.step() } }
   fun count(sql: String): Int = c.prepare(sql).use { var n=0;while(it.step()) n++;n }
   c.prepare("ATTACH DATABASE ? AS catalog").use { it.bindText(1,File(root,"$id/catalog.db").toURI().toASCIIString()+"?mode=ro&immutable=1");it.step() }
   exec("PRAGMA query_only=ON")
   val prefix="WITH scope AS MATERIALIZED (SELECT t.row_id,t.year FROM catalog.titles t INDEXED BY ix_titles_facets WHERE t.id NOT IN (SELECT u.title_id FROM user_state u WHERE u.hidden=1 AND u.title_id IS NOT NULL) AND t.row_id IN(SELECT rowid FROM catalog.titles_fts WHERE titles_fts MATCH '\"classic\"*')) "
   val compact="SELECT o.facet,o.key,o.label,a.n FROM (SELECT m.option_id,count(*) n FROM scope s JOIN catalog.facet_members m ON m.title_row=s.row_id GROUP BY m.option_id) a JOIN catalog.facet_options o ON o.id=a.option_id"
   val direct=listOf("provider" to "providers","country" to "countries","studio" to "studios").map { (g,t) -> "SELECT '$g',coalesce(a.key,'@unknown'),coalesce(v.label,'Unknown'),a.n FROM (SELECT r.key,count(*) n FROM scope s LEFT JOIN catalog.title_$t r ON r.title_row=s.row_id GROUP BY r.key) a LEFT JOIN catalog.$t v ON v.key=a.key" }.plus("SELECT 'decade',coalesce(CAST(s.year/10*10 AS TEXT),'@unknown'),coalesce(CAST(s.year/10*10 AS TEXT),'Unknown'),count(*) FROM scope s GROUP BY s.year/10").joinToString(" UNION ALL ")
   for(mode in listOf(0,2)) {
    exec("PRAGMA temp_store=$mode")
    for((name,sql) in listOf("compact" to compact,"compact_cross" to compact.replace("scope s JOIN","scope s CROSS JOIN"),"relations" to direct)) {
     c.prepare("EXPLAIN QUERY PLAN $prefix$sql").use { s -> while(s.step()) Log.i("EroflixP02","PLAN $mode $name "+s.getText(3)) }
     repeat(3) { val start=SystemClock.elapsedRealtimeNanos();val n=count(prefix+sql);Log.i("EroflixP02","PROBE $mode $name $it "+(SystemClock.elapsedRealtimeNanos()-start)/1e6+" rows=$n");assertTrue(n>0) }
    }
   }
  }
 }
}
