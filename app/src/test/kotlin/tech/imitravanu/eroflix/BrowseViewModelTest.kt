package tech.imitravanu.eroflix

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import tech.imitravanu.eroflix.testing.*
import tech.imitravanu.eroflix.feature.catalog.BrowseState

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun restoredSearchAndSubsequentSearchProduceCorrectResults() = runTest(main.dispatcher) {
        val personal = FixturePersonalRepository()
        val handle = SavedStateHandle(mapOf("search" to "River"))
        val model = BrowseViewModel(FixtureCatalogRepository(personal), personal, handle, main.dispatcher)
        val store = ViewModelStore().also { it.put("browse", model) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            advanceTimeBy(300); runCurrent()
            assertEquals("River Journey", (model.state.value as BrowseState.Ready).titles.single().title)
            model.search("Aurora"); advanceTimeBy(300); runCurrent()
            assertEquals("Aurora", handle.get<String>("search"))
            assertEquals("Aurora Field Notes", (model.state.value as BrowseState.Ready).titles.single().title)
        } finally { store.clear() }
    }

    @Test fun hiddenChangeInvalidatesAlreadyVisibleResults() = runTest(main.dispatcher) {
        val personal = FixturePersonalRepository()
        val model = BrowseViewModel(FixtureCatalogRepository(personal), personal, SavedStateHandle(), main.dispatcher)
        val store = ViewModelStore().also { it.put("browse", model) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            advanceTimeBy(300); runCurrent()
            assertEquals(3, (model.state.value as BrowseState.Ready).titles.size)
            personal.setHidden(fixtureTitles.first(), true, 1); runCurrent()
            assertEquals(2, (model.state.value as BrowseState.Ready).titles.size)
        } finally { store.clear() }
    }
}
