package tech.imitravanu.eroflix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Before
import org.junit.Assume
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.model.*
import tech.imitravanu.eroflix.core.data.PersonalStorage
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Run these two methods in separate instrumentation processes, with force-stop between. */
@RunWith(AndroidJUnit4::class)
class PersonalRestartTest {
    @Before fun explicitTwoProcessRun() { Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartProbe")=="true") }
    private val title=TitleSummary(TitleId("fixture:p02-restart-probe"),"Neutral persistence probe",2000)
    private suspend fun container(): AppContainer {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EroflixApplication
        return (withTimeout(180_000) { app.provision.first { it is ProvisionState.Ready || it is ProvisionState.Failed } } as ProvisionState.Ready).container
    }
    @Test fun seedBeforeProcessDeath()=runBlocking {
        val p=container().personal
        assertTrue(p.setFavorite(title,true) is Outcome.Success);assertTrue(p.setSaved(title,true) is Outcome.Success)
        assertTrue(p.recordProgress(PlaybackObservation(title,SourceId("fixture-source"),"fixture-timeline",12345,60000,1700000000000)) is Outcome.Success)
        assertEquals(12345L,p.observeState(title.id).first().positionMs)
    }
    @Test fun verifyAfterProcessDeathAndClearProbe()=runBlocking {
        val p=container().personal;val state=p.observeState(title.id).first()
        assertTrue(state.favorite);assertTrue(state.saved);assertEquals(12345L,state.positionMs);assertEquals(60000L,state.durationMs)
        assertTrue(p.observeShelf(LibraryShelf.FAVORITES).first().any { it.title==title })
        // Remove only the neutral probe after the production writer is closed.
        val store=p as PersonalStorage;store.close()
        BundledSQLiteDriver().open(store.file.absolutePath).use { c ->
            fun exec(sql: String) { c.prepare(sql).use { it.step() } }
            exec("BEGIN IMMEDIATE")
            for(table in listOf("user_state","retained_titles")) c.prepare("DELETE FROM $table WHERE title_id=?").use { s -> s.bindText(1,title.id.value);s.step() }
            exec("UPDATE personal_meta SET revision=revision+1 WHERE id=1");exec("COMMIT")
        }
    }
}
