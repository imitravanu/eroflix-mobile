package tech.imitravanu.eroflix.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tech.imitravanu.eroflix.model.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().context
    private lateinit var root: File
    private lateinit var generations: GenerationStorage
    private lateinit var personal: PersonalStorage
    private lateinit var catalog: CatalogStorage
    private fun schema()=context.assets.open("schema/personal-v1.sql").bufferedReader().use { it.readText() }
    private suspend fun openStorage(fail: (String)->Unit = {}) {
        generations=GenerationStorage(File(root,"generations"),failurePoint=fail);generations.initialize()
        personal=PersonalStorage(File(root,"personal.db"),generations,schema());personal.initialize()
        catalog=CatalogStorage(generations,personal)
    }
    private fun <T> ok(r: Outcome<T>): T = when(r) { is Outcome.Success -> r.value;is Outcome.Failure -> throw AssertionError(r.error.toString()) }
    private fun manifest(v: Int)=parseCatalogManifest(context.assets.open("fixture-v$v/manifest.json").bufferedReader().use { it.readText() })
    private suspend fun stage(v: Int): Outcome<GenerationId> {
        val artifact=File(root,"fixture$v.gz");context.assets.open("fixture-v$v/catalog.bin").use { input -> artifact.outputStream().use { input.copyTo(it) } }
        return generations.stage(manifest(v),artifact.absolutePath)
    }
    private suspend fun activate(v: Int) { ok(stage(v));ok(generations.activate(manifest(v).generationId)) }
    @Before fun setup()=runBlocking { root=File(context.cacheDir,"p02-${UUID.randomUUID()}");root.mkdirs();openStorage();activate(1) }
    @After fun cleanup() { personal.close();root.deleteRecursively() }
    private val aurora get()=TitleSummary(TitleId("fixture:aurora"),"Aurora Field Notes",1984)
    @Test fun watchedCoverageExcludesSeeksAndRespectsManualUnhideAcrossRestart() = runBlocking {
        ok(personal.setFavorite(aurora,true));ok(personal.setAutoHide(true))
        fun observation(position: Long,from: Long?, discontinuity: Boolean=false, duration: Long=1000) =
            PlaybackObservation(aurora,SourceId("source"),"timeline",position,duration,1000,from,discontinuity)
        ok(personal.recordProgress(observation(1000,null,true)))
        assertFalse(personal.observeState(aurora.id).first().hidden)
        ok(personal.recordProgress(observation(900,0)))
        assertFalse(personal.observeState(aurora.id).first().hidden)
        ok(personal.recordProgress(observation(900,0))) // replay does not double-count
        assertFalse(personal.observeState(aurora.id).first().hidden)
        personal.close();openStorage()
        assertTrue(personal.observeAutoHide().first())
        ok(personal.recordProgress(observation(901,900)))
        assertTrue(personal.observeState(aurora.id).first().hidden)
        ok(personal.setHidden(aurora,false,2000))
        ok(personal.recordProgress(observation(1000,901)))
        assertFalse(personal.observeState(aurora.id).first().hidden)
        assertTrue(personal.observeState(aurora.id).first().favorite)
        ok(personal.startWatchCycle(aurora))
        ok(personal.recordProgress(observation(900,0, duration=2000)))
        assertFalse(personal.observeState(aurora.id).first().hidden)
        ok(personal.recordProgress(observation(1801,900, duration=2000)))
        assertTrue(personal.observeState(aurora.id).first().hidden)
    }
    @Test fun autoHideHandlesUnknownAndShortDurationsWithoutMixingTimelines() = runBlocking {
        ok(personal.setAutoHide(true))
        fun record(timeline: String,position: Long,from: Long?,duration: Long?) =
            PlaybackObservation(aurora,SourceId("source"),timeline,position,duration,1000,from)
        ok(personal.recordProgress(record("a",98,0,null)))
        ok(personal.recordProgress(record("a",45,0,99)))
        ok(personal.recordProgress(record("b",90,45,99)))
        assertFalse(personal.observeState(aurora.id).first().hidden)
        ok(personal.recordProgress(record("a",90,45,99)))
        assertTrue(personal.observeState(aurora.id).first().hidden)
    }
    @Test fun autoHideMigrationFailurePreservesExistingData() = runBlocking {
        ok(personal.setFavorite(aurora,true)); personal.close()
        BundledSQLiteDriver().open(personal.file.absolutePath).use { c -> c.exec("DROP TABLE personal_settings");c.exec("PRAGMA user_version=2") }
        val failed=PersonalStorage(personal.file,generations,schema()) { if(it==3) error("injected migration failure") }
        assertTrue(runCatching { failed.initialize() }.isFailure)
        BundledSQLiteDriver().open(personal.file.absolutePath).use { c ->
            assertEquals(2L,c.long("PRAGMA user_version"));assertEquals(1L,c.long("SELECT favorite FROM user_state WHERE title_id=?",listOf(aurora.id.value)))
        }
        personal=PersonalStorage(personal.file,generations,schema());personal.initialize()
        assertTrue(personal.observeState(aurora.id).first().favorite)
        assertFalse(personal.observeAutoHide().first())
    }
    @Test fun libraryPagesSearchLiteralTitlesAndRespectHiddenState() = runBlocking {
        val titles=listOf(aurora, TitleSummary(TitleId("local:one"),"100% Real"), TitleSummary(TitleId("local:two"),"Another title"))
        titles.forEach { ok(personal.setFavorite(it,true)) }
        val first=personal.observeShelfPage(LibraryShelf.FAVORITES,"",0,2).first()
        val next=personal.observeShelfPage(LibraryShelf.FAVORITES,"",2,2).first()
        assertEquals(2,first.size);assertEquals(1,next.size)
        assertEquals(titles.map { it.id }.toSet(),(first+next).map { it.title.id }.toSet())
        assertEquals("100% Real",personal.observeShelfPage(LibraryShelf.FAVORITES,"%",0,10).first().single().title.title)
        ok(personal.setHidden(titles[1],true,1234))
        assertEquals(2,personal.observeShelfPage(LibraryShelf.FAVORITES,"",0,10).first().size)
        assertEquals(titles[1].id,personal.observeShelfPage(LibraryShelf.HIDDEN,"100%",0,10).first().single().title.id)
        ok(personal.unhideAll())
        assertEquals(3,personal.observeShelfPage(LibraryShelf.FAVORITES,"",0,10).first().size)
    }
    @Test fun durableDownloadIntentPreservesExactSourceTypedTracksAndPersonalStateAfterReopen()=runBlocking {
        ok(personal.setFavorite(aurora,true))
        val source=ok(catalog.details(aurora.id)).sources.first()
        val native=Resolution.NativeMedia("https://fixture.invalid/selected?token=static","video/mp4",listOf(RequestScope(setOf("fixture.invalid"),mapOf("Referer" to "https://fixture.invalid/"))),null,"resource-identity",null,emptyList(),MediaCapabilities(true,true,false),ResourceValidators("\"strong\"",null,5L*1024*1024*1024,true))
        val selected=listOf(DownloadTrackRef(0,0,1,2,"same-id","Video",720),DownloadTrackRef(0,1,0,1,"same-id","Audio",language="en"))
        val record=PersistedDownload(JobId("download-fixture"),DownloadIntent(aurora,source,TrackSelection(setOf("same-id"))),selected,native,8,DownloadDesired.PAUSE_USER,"Paused by you",5L*1024*1024*1024,listOf(DownloadStreamRef(0,0,1),DownloadStreamRef(0,1,0)))
        ok(personal.putDownload(record));personal.close();openStorage()
        assertEquals(record,ok(personal.downloads()).single())
        assertTrue(personal.observeState(aurora.id).first().favorite)
        ok(personal.putDownload(record.copy(desired=DownloadDesired.RUN,reason=null)))
        assertEquals(8L,ok(personal.downloads()).single().order)
        ok(personal.deleteDownload(record.id));assertTrue(ok(personal.downloads()).isEmpty())
        assertTrue(personal.observeState(aurora.id).first().favorite)
    }
    @Test fun discoveryMetadataAndFilteredCountsUseLeasedGenerationAndPersonalRevision()=runBlocking {
        val metadata=ok(catalog.metadata())
        assertEquals(generations.active.value,metadata.generation)
        assertEquals(5L,metadata.titleCount)
        assertEquals(manifest(1).sourceCount,metadata.sourceCount)
        val entity=ok(catalog.details(aurora.id)).entities.first()
        val queries=listOf(CatalogQuery(), CatalogQuery(entity=entity),
            CatalogQuery(countries=setOf(FacetValue.Known("fixture country")),decades=setOf(FacetValue.Known("1980"))),
            CatalogQuery(duration=DurationFilter(onlyUnknown=true)))
        for(q in queries) {
            val count=ok(catalog.count(q));val page=ok(catalog.query(q))
            assertEquals(page.stamp,count.stamp);assertEquals(page.items.size.toLong(),count.titleCount)
        }
        ok(personal.setHidden(aurora,true,1))
        val hiddenCount=ok(catalog.count(CatalogQuery()))
        assertEquals(4L,hiddenCount.titleCount)
        assertEquals(ok(catalog.query(CatalogQuery())).stamp,hiddenCount.stamp)
        // Total catalog metadata deliberately includes hidden records; matching count excludes them.
        assertEquals(5L,ok(catalog.metadata()).titleCount)
        activate(2)
        assertEquals(generations.active.value,ok(catalog.metadata()).generation)
        assertEquals(generations.active.value,ok(catalog.count(CatalogQuery())).stamp.generation)
    }
    @Test fun exactSourcesUnicodeBoundedSearchAndKeysetOrdering()=runBlocking {
        val d=ok(catalog.details(aurora.id));assertEquals(sourceId(aurora.id,"fixture",StoredSourceKind.EMBED,d.sources.first().originalUrl),d.sources.first().id)
        for(q in listOf("Café","cafe","東京","京物","東","Записки","\"OR\"","stars*","* OR (NOT)")) assertTrue(catalog.query(CatalogQuery(search=q)) is Outcome.Success)
        assertEquals("fixture:unicode",ok(catalog.query(CatalogQuery(search="京物"))).items.single().id.value)
        assertEquals("fixture:unicode",ok(catalog.query(CatalogQuery(search="cafe"))).items.single().id.value)
        assertTrue(catalog.query(CatalogQuery(search="x".repeat(257))) is Outcome.Failure)
        for(sort in CatalogSort.entries) {
            val expected=ok(catalog.query(CatalogQuery(sort=sort))).items.map { it.id }
            val actual=mutableListOf<TitleId>();var cursor: PageCursor?=null
            do { val p=ok(catalog.query(CatalogQuery(sort=sort,limit=1,cursor=cursor)));actual+=p.items.map { it.id };cursor=p.next } while(cursor!=null)
            assertEquals(expected,actual);assertEquals(5,actual.distinct().size)
        }
        val unknown=ok(catalog.query(CatalogQuery(decades=setOf(FacetValue.Unknown))));assertEquals("fixture:observatory",unknown.items.single().id.value)
    }
    @Test fun facetsHiddenExclusionAndConcurrentReadsHaveConsistentStamps()=runBlocking {
        val q=CatalogQuery(countries=setOf(FacetValue.Known("fixture country")),decades=setOf(FacetValue.Known("1980")))
        assertEquals(listOf(aurora.id),ok(catalog.query(q)).items.map { it.id })
        val counts=ok(catalog.facets(q));assertEquals(1L,counts.countries.first { it.value==FacetValue.Unknown }.titleCount)
        assertEquals(1L,counts.decades.first { it.value==FacetValue.Known("1990") }.titleCount)
        val cursor=ok(catalog.query(CatalogQuery(limit=1))).next!!
        ok(personal.setFavorite(aurora,true));ok(personal.setSaved(aurora,true));ok(personal.setHidden(aurora,true,1000))
        assertFalse(ok(catalog.query(CatalogQuery())).items.any { it.id==aurora.id })
        assertTrue(catalog.query(CatalogQuery(limit=1,cursor=cursor)) is Outcome.Failure)
        assertEquals(0L,ok(catalog.facets(q)).decades.first { it.value==FacetValue.Known("1980") }.titleCount)
        assertTrue(personal.observeShelf(LibraryShelf.FAVORITES).first().isEmpty())
        assertEquals(aurora.id,personal.observeShelf(LibraryShelf.HIDDEN).first().single().title.id)
        coroutineScope {
            val reads=(0 until 12).map { async(Dispatchers.IO) { ok(catalog.query(CatalogQuery())).also { page -> assertFalse(page.items.any { it.id==aurora.id }) } } }
            reads.awaitAll()
        }
        ok(personal.unhideAll());assertTrue(personal.observeState(aurora.id).first().saved)
        assertEquals(1,personal.observeShelf(LibraryShelf.FAVORITES).first().size)
    }
    @Test fun optimizedFacetsMatchDiscoveryForSearchSingleAndMultipleSelections()=runBlocking {
        ok(personal.setHidden(aurora,true,1000))
        val cases=listOf(CatalogQuery(search="cafe"),CatalogQuery(search="Notes"),CatalogQuery(countries=setOf(FacetValue.Unknown)),
            CatalogQuery(studios=setOf(FacetValue.Known("fixture-archive"))),CatalogQuery(countries=setOf(FacetValue.Unknown,FacetValue.Known("fixture country"))))
        for(q in cases) {
            val f=ok(catalog.facets(q))
            for((group,options) in listOf("provider" to f.providers,"country" to f.countries,"decade" to f.decades,"studio" to f.studios)) for(option in options) {
                val selected=setOf(option.value)
                val matching=when(group) { "provider" -> q.copy(providers=selected);"country" -> q.copy(countries=selected);"decade" -> q.copy(decades=selected);else -> q.copy(studios=selected) }
                assertEquals("$group ${option.value}",ok(catalog.query(matching)).items.size.toLong(),option.titleCount)
            }
        }
    }
    @Test fun readerAttachmentIsActuallyReadOnlyAndWriterDoesNotAttachCatalog()=runBlocking {
        generations.read(personal.file) { c,_ ->
            assertEquals(1L,c.long("PRAGMA query_only"))
            assertEquals(5L,c.long("SELECT count(*) FROM catalog.titles"))
            // Turning query_only off must still not grant writes to the attached immutable file.
            c.exec("PRAGMA query_only=OFF")
            assertTrue(runCatching { c.exec("UPDATE catalog.titles SET title='bad'") }.isFailure)
        }
        ok(personal.setSaved(aurora,true));assertEquals(1,personal.observeShelf(LibraryShelf.SAVED).first().size)
    }
    @Test fun absentTitleAndProgressSurviveActivationRollbackAndProcessReopen()=runBlocking {
        ok(personal.setFavorite(aurora,true));ok(personal.setSaved(aurora,true))
        ok(personal.recordProgress(PlaybackObservation(aurora,SourceId("test-source"),"timeline",12345,60000,1700000000123)))
        val before=personal.observeState(aurora.id).first();activate(2)
        assertEquals(aurora.title,personal.observeShelf(LibraryShelf.FAVORITES).first().single().title.title)
        assertTrue(ok(catalog.details(aurora.id)).sources.isNotEmpty())
        personal.close();openStorage();assertEquals(GenerationId("fixture-v2"),generations.active.value);assertEquals(before,personal.observeState(aurora.id).first())
        ok(generations.rollback());assertEquals(GenerationId("fixture-v1"),generations.active.value);assertEquals(before,personal.observeState(aurora.id).first())
    }
    @Test fun migrationFailureRollsBackAndPreservesSemanticState()=runBlocking {
        ok(personal.setFavorite(aurora,true));val before=personal.observeState(aurora.id).first();personal.close()
        BundledSQLiteDriver().open(personal.file.absolutePath).use { c -> c.exec("ALTER TABLE user_state DROP COLUMN manual_unhide_ms");c.exec("PRAGMA user_version=1") }
        val failed=PersonalStorage(personal.file,generations,schema()) { error("injected migration failure") }
        assertTrue(runCatching { failed.initialize() }.isFailure)
        BundledSQLiteDriver().open(personal.file.absolutePath).use { c -> assertEquals(1L,c.long("PRAGMA user_version"));assertEquals(1L,c.long("SELECT favorite FROM user_state WHERE title_id='fixture:aurora'")) }
        personal=PersonalStorage(personal.file,generations,schema());personal.initialize();assertEquals(before,personal.observeState(aurora.id).first())
    }
    @Test fun interruptedStagingActivationAndCorruptCurrentRecoverWithoutPersonalLoss()=runBlocking {
        ok(personal.setFavorite(aurora,true));ok(personal.setHidden(aurora,true,1000));val before=personal.observeState(aurora.id).first()
        for(point in listOf("compressed-verified","extracted","validated","files-synced","generation-renamed","reopened","pointer-synced","pointer-published")) {
            personal.close();openStorage { if(it==point) error("injected $point") }
            // A renamed validated candidate is reusable after a crash, as is a published pointer.
            val stageResult=stage(2)
            if(stageResult is Outcome.Success) generations.activate(manifest(2).generationId)
            personal.close();openStorage()
            assertTrue(generations.active.value in listOf(GenerationId("fixture-v1"),GenerationId("fixture-v2")))
            assertEquals(before,personal.observeState(aurora.id).first())
            if(generations.active.value==GenerationId("fixture-v2")) ok(generations.rollback())
            File(root,"generations/fixture-v2").deleteRecursively()
        }
        activate(2);personal.close()
        File(root,"generations/fixture-v2/catalog.db").outputStream().use { it.write(byteArrayOf(0)) }
        openStorage();assertEquals(GenerationId("fixture-v1"),generations.active.value);assertEquals(before,personal.observeState(aurora.id).first())
    }
    @Test fun hideWritesAndQueriesUseTheSameRevisionSnapshot()=runBlocking {
        coroutineScope {
            val writes=launch(Dispatchers.IO) { repeat(20) { ok(personal.setHidden(aurora,it%2==0,it.toLong())) } }
            val reads=(0 until 3).map { async(Dispatchers.IO) { repeat(30) {
                val page=ok(catalog.query(CatalogQuery()))
                assertEquals(page.stamp.personalRevision%2==0L,page.items.any { it.id==aurora.id })
            } } }
            writes.join();reads.awaitAll();Unit
        }
    }
    @Test fun activationWaitsForLeasesAndOlderBundleDoesNotReplaceNewerCatalog()=runBlocking {
        ok(stage(2));val entered=CompletableDeferred<Unit>()
        coroutineScope {
            val read=async(Dispatchers.IO) { generations.read(personal.file) { _,id -> entered.complete(Unit);Thread.sleep(100);id } }
            entered.await()
            val activate=async { ok(generations.activate(GenerationId("fixture-v2"))) }
            assertEquals(GenerationId("fixture-v1"),read.await());activate.await()
        }
        assertFalse(generations.needsBundledGeneration(manifest(1).copy(createdAtEpochMs=-1)))
        assertTrue(generations.needsBundledGeneration(manifest(1).copy(createdAtEpochMs=1)))
        ok(generations.cleanObsolete());assertEquals(2,generations.retainedGenerations().size)
    }
    @Test fun boundedExpansionAndLowSpaceLeavePreviousGenerationIntact()=runBlocking {
        val file=File(root,"candidate.gz");context.assets.open("fixture-v2/catalog.bin").use { input -> file.outputStream().use { input.copyTo(it) } }
        assertTrue(generations.stage(manifest(2).copy(uncompressedBytes=10),file.absolutePath) is Outcome.Failure)
        val lowSpace=GenerationStorage(File(root,"generations"),availableBytes={0});lowSpace.initialize()
        val r=lowSpace.stage(manifest(2),file.absolutePath)
        assertEquals(ErrorCode.STORAGE_FULL,(r as Outcome.Failure).error.code)
        assertEquals(5,ok(catalog.query(CatalogQuery())).items.size)
    }
    @Test fun corruptCompressedInputIsRejectedAndActiveQueryRemainsUsable()=runBlocking {
        val m=manifest(2);val bad=File(root,"bad.gz");bad.writeBytes(byteArrayOf(1,2,3))
        assertTrue(generations.stage(m,bad.absolutePath) is Outcome.Failure)
        assertEquals(GenerationId("fixture-v1"),generations.active.value);assertEquals(5,ok(catalog.query(CatalogQuery())).items.size)
        assertTrue(generations.stage(m.copy(schemaVersion=99),bad.absolutePath) is Outcome.Failure)
    }
}
