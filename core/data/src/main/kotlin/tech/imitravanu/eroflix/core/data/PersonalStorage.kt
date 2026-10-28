package tech.imitravanu.eroflix.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import tech.imitravanu.eroflix.model.*
import java.io.File

/** One durable writer; migrations and each state/revision update commit together. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalStorage(val file: File,private val generations: GenerationStorage,
    private val schema: String,private val migrationFailure: (Int)->Unit = {}) : PersonalRepository,DownloadIntentStore,ExportIntentStore,AutoCloseable {
    private val driver=BundledSQLiteDriver()
    private val writerMutex=Mutex()
    private val readers=Semaphore(3)
    private lateinit var writer: SQLiteConnection
    private val mutable=MutableStateFlow(0L)
    override val revision: StateFlow<Long> = mutable.asStateFlow()
    suspend fun initialize()=withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs();writer=driver.open(file.absolutePath)
        try {
            writer.exec("PRAGMA busy_timeout=5000");writer.exec("PRAGMA journal_mode=WAL");writer.exec("PRAGMA synchronous=FULL");writer.exec("PRAGMA foreign_keys=ON")
            val version=writer.long("PRAGMA user_version").toInt()
            requireData(version in 0..3,ErrorCode.MIGRATION_FAILED,"Personal data was created by a newer app. Keep it and update the app.")
            writer.exec("BEGIN IMMEDIATE")
            try {
                if(version==0) schema.split(';').filter { it.isNotBlank() }.forEach { writer.exec(it) }
                if(version<2) {
                    writer.exec("ALTER TABLE user_state ADD COLUMN manual_unhide_ms INTEGER")
                    migrationFailure(2);writer.exec("PRAGMA user_version=2")
                }
                if(version<3) {
                    writer.exec("CREATE TABLE IF NOT EXISTS personal_settings(id INTEGER PRIMARY KEY CHECK(id=1),auto_hide INTEGER NOT NULL DEFAULT 0)")
                    writer.exec("INSERT OR IGNORE INTO personal_settings(id,auto_hide) VALUES(1,0)")
                    migrationFailure(3);writer.exec("PRAGMA user_version=3")
                }
                writer.exec("COMMIT")
            } catch(e: Exception) { writer.exec("ROLLBACK");throw DataFailure(AppError(ErrorCode.MIGRATION_FAILED,"Personal-data migration failed. Existing data was preserved.",true)) }
            mutable.value=writer.long("SELECT revision FROM personal_meta WHERE id=1")
        } catch(e: Exception) { writer.close();throw e }
    }
    private suspend fun <T> read(block: (SQLiteConnection)->T): T = withContext(Dispatchers.IO) { readers.withPermit {
        driver.open(file.absolutePath,1 or 64).use { c -> c.exec("PRAGMA busy_timeout=5000");c.exec("PRAGMA query_only=ON");block(c) }
    } }
    override fun observeState(titleId: TitleId): Flow<PersonalState> = revision.mapLatest {
        read { c -> c.rows("SELECT favorite,saved,hidden,hidden_at_ms,position_ms,duration_ms,last_watched_ms FROM user_state WHERE title_id=?",listOf(titleId.value)) { it.personal() }.firstOrNull() ?: PersonalState() }
    }.distinctUntilChanged()
    override fun observeShelf(shelf: LibraryShelf): Flow<List<LibraryItem>> = observeShelfPage(shelf,"",0,120)
    override fun observeShelfPage(shelf: LibraryShelf, query: String, offset: Int, limit: Int): Flow<List<LibraryItem>> = combine(revision,generations.active) { _,_ -> Unit }.mapLatest {
        val condition=when(shelf) {
            LibraryShelf.FAVORITES -> "u.hidden=0 AND u.favorite=1"
            LibraryShelf.SAVED -> "u.hidden=0 AND u.saved=1"
            LibraryShelf.HISTORY -> "u.hidden=0 AND u.last_watched_ms IS NOT NULL"
            LibraryShelf.CONTINUE_WATCHING -> "u.hidden=0 AND u.position_ms>0 AND (u.duration_ms IS NULL OR u.position_ms<u.duration_ms)"
            LibraryShelf.HIDDEN -> "u.hidden=1"
        }
        generations.read(file) { c,_ -> c.rows("""SELECT r.title_id,coalesce(t.title,r.title),coalesce(t.year,r.year),coalesce(t.duration_ms,r.duration_ms),coalesce(t.poster_url,r.poster_url),
            u.favorite,u.saved,u.hidden,u.hidden_at_ms,u.position_ms,u.duration_ms,u.last_watched_ms
            FROM user_state u JOIN retained_titles r ON r.title_id=u.title_id LEFT JOIN catalog.titles t ON t.id=u.title_id
            WHERE $condition AND instr(lower(coalesce(t.title,r.title)),lower(?))>0
            ORDER BY coalesce(u.last_watched_ms,u.hidden_at_ms,0) DESC,r.title_id LIMIT ? OFFSET ?""", listOf(query.trim(),limit.coerceIn(1,121),offset.coerceAtLeast(0))) { LibraryItem(it.summary(),it.personal(5)) } }
    }.distinctUntilChanged()
    private suspend fun snapshot(title: TitleSummary): String? = runCatching {
        generations.read(file) { c,_ ->
            val details=readDetails(c,title.id) ?: return@read null
            detailsJson(details)
        }
    }.getOrElse { if(it is CancellationException) throw it;null }
    private suspend fun change(title: TitleSummary?,action: (SQLiteConnection)->Unit): Outcome<Unit> = outcome {
        val details=title?.let { snapshot(it) }
        withContext(Dispatchers.IO) { writerMutex.withLock {
            writer.exec("BEGIN IMMEDIATE")
            try {
                title?.let {
                    writer.exec("INSERT INTO retained_titles(title_id,title,year,duration_ms,poster_url,details_json) VALUES (?,?,?,?,?,?) ON CONFLICT(title_id) DO UPDATE SET title=excluded.title,year=excluded.year,duration_ms=excluded.duration_ms,poster_url=excluded.poster_url,details_json=coalesce(excluded.details_json,retained_titles.details_json)",it.id.value,it.title,it.year,it.durationMs,it.posterUrl,details)
                    writer.exec("INSERT OR IGNORE INTO user_state(title_id) VALUES (?)",it.id.value)
                }
                action(writer);writer.exec("UPDATE personal_meta SET revision=revision+1 WHERE id=1")
                val next=writer.long("SELECT revision FROM personal_meta WHERE id=1")
                writer.exec("COMMIT");mutable.value=next
            } catch(e: Exception) { writer.exec("ROLLBACK");throw e }
        } }
    }
    override suspend fun setFavorite(title: TitleSummary,enabled: Boolean)=change(title) { it.exec("UPDATE user_state SET favorite=? WHERE title_id=?",enabled,title.id.value) }
    override suspend fun setSaved(title: TitleSummary,enabled: Boolean)=change(title) { it.exec("UPDATE user_state SET saved=? WHERE title_id=?",enabled,title.id.value) }
    override suspend fun setHidden(title: TitleSummary,enabled: Boolean,atEpochMs: Long)=change(title) {
        it.exec("UPDATE user_state SET hidden=?,hidden_at_ms=?,auto_hide_suppressed=CASE WHEN ?=0 THEN 1 ELSE auto_hide_suppressed END,manual_unhide_ms=CASE WHEN ?=0 THEN ? ELSE manual_unhide_ms END WHERE title_id=?",enabled,if(enabled) atEpochMs else null,enabled,enabled,atEpochMs,title.id.value)
    }
    override suspend fun unhideAll()=change(null) { it.exec("UPDATE user_state SET hidden=0,hidden_at_ms=NULL,auto_hide_suppressed=1,manual_unhide_ms=? WHERE hidden=1",System.currentTimeMillis()) }
    override fun observeAutoHide(): Flow<Boolean> = revision.mapLatest { read { it.long("SELECT auto_hide FROM personal_settings WHERE id=1") != 0L } }.distinctUntilChanged()
    override suspend fun setAutoHide(enabled: Boolean)=change(null) { it.exec("UPDATE personal_settings SET auto_hide=? WHERE id=1",enabled) }
    override suspend fun startWatchCycle(title: TitleSummary)=change(title) { c ->
        c.exec("DELETE FROM watched_intervals WHERE title_id=?",title.id.value)
        c.exec("UPDATE user_state SET watch_cycle=?,watched=0,auto_hide_suppressed=0 WHERE title_id=?",java.util.UUID.randomUUID().toString(),title.id.value)
    }
    override suspend fun recordProgress(observation: PlaybackObservation)=change(observation.title) { c ->
        val id=observation.title.id.value
        c.exec("UPDATE user_state SET position_ms=?,duration_ms=?,last_watched_ms=? WHERE title_id=?",observation.positionMs,observation.durationMs,observation.atEpochMs,id)
        val duration=observation.durationMs
        val start=observation.playedFromMs
        if (!observation.discontinuity && duration != null && duration>0 && start != null && start<observation.positionMs && start<duration) {
            val end=observation.positionMs.coerceAtMost(duration)
            val cycle=c.rows("SELECT watch_cycle FROM user_state WHERE title_id=?",listOf(id)) { it.getText(0) }.single()
            // Duration is part of the identity: incompatible timelines must not share coverage.
            val timeline="${observation.timelineId.length}:${observation.timelineId}:$duration"
            val overlaps=c.rows("SELECT start_ms,end_ms FROM watched_intervals WHERE title_id=? AND timeline_id=? AND watch_cycle=? AND end_ms>=? AND start_ms<=?",listOf(id,timeline,cycle,start,end)) { it.getLong(0) to it.getLong(1) }
            val mergedStart=overlaps.fold(start) { value,range -> minOf(value,range.first) }
            val mergedEnd=overlaps.fold(end) { value,range -> maxOf(value,range.second) }
            c.exec("DELETE FROM watched_intervals WHERE title_id=? AND timeline_id=? AND watch_cycle=? AND end_ms>=? AND start_ms<=?",id,timeline,cycle,start,end)
            c.exec("INSERT OR REPLACE INTO watched_intervals(title_id,timeline_id,watch_cycle,start_ms,end_ms) VALUES(?,?,?,?,?)",id,timeline,cycle,mergedStart,mergedEnd)
            // Discard excess ranges rather than inventing coverage across gaps.
            c.exec("DELETE FROM watched_intervals WHERE rowid IN (SELECT rowid FROM watched_intervals WHERE title_id=? ORDER BY end_ms DESC LIMIT -1 OFFSET 1024)",id)
            val covered=c.long("SELECT coalesce(sum(end_ms-start_ms),0) FROM watched_intervals WHERE title_id=? AND timeline_id=? AND watch_cycle=?",listOf(id,timeline,cycle))
            val ninetyPercentFloor=(duration/10)*9 + (duration%10)*9/10
            if (covered > ninetyPercentFloor) {
                c.exec("UPDATE user_state SET watched=1 WHERE title_id=?",id)
                if(c.long("SELECT auto_hide FROM personal_settings WHERE id=1")!=0L)
                    c.exec("UPDATE user_state SET hidden=1,hidden_at_ms=? WHERE title_id=? AND hidden=0 AND auto_hide_suppressed=0",observation.atEpochMs,id)
            }
        }
    }
    override suspend fun downloads(): Outcome<List<PersistedDownload>> = outcome {
        read { c -> c.rows("SELECT job_id,source_snapshot,selected_tracks,enqueue_order,desired_state,user_stop_reason FROM download_intents ORDER BY enqueue_order,job_id") {
            restoredDownload(it.getText(0),it.getText(1),it.getText(2),it.getLong(3),it.getText(4),it.textOrNull(5))
        } }
    }
    override suspend fun putDownload(record: PersistedDownload)=change(record.intent.title) { c ->
        c.exec("""INSERT INTO download_intents(job_id,media3_request_id,title_id,source_snapshot,selected_tracks,enqueue_order,user_stop_reason,desired_state,updated_at_ms)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET source_snapshot=excluded.source_snapshot,selected_tracks=excluded.selected_tracks,
            user_stop_reason=excluded.user_stop_reason,desired_state=excluded.desired_state,updated_at_ms=excluded.updated_at_ms""",
            record.id.value,record.id.value,record.intent.title.id.value,record.snapshotJson(),record.selected.trackJson(),record.order,record.reason,record.desired.name,System.currentTimeMillis())
    }
    override suspend fun deleteDownload(id: JobId)=change(null) { it.exec("DELETE FROM download_intents WHERE job_id=?",id.value) }
    override suspend fun exportIntents(): Outcome<List<PersistedExport>> = outcome {
        read { c -> c.rows("SELECT job_id,download_id,destination_uri,staging_reference,state,error,created_at_ms FROM export_jobs ORDER BY created_at_ms,job_id") {
            PersistedExport(ExportJob(JobId(it.getText(0)),JobId(it.getText(1)),it.textOrNull(2).orEmpty(),ExportStatus.valueOf(it.getText(4)),
                it.textOrNull(5)?.let { message -> AppError(ErrorCode.EXPORT_FAILED,message,true) }),it.textOrNull(3),it.getLong(6))
        } }
    }
    override suspend fun putExport(record: PersistedExport)=change(null) { c ->
        val job=record.job
        c.exec("""INSERT INTO export_jobs(job_id,download_id,destination_uri,mime_type,container,staging_reference,state,error,created_at_ms,updated_at_ms)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET staging_reference=excluded.staging_reference,state=excluded.state,error=excluded.error,updated_at_ms=excluded.updated_at_ms""",
            job.id.value,job.downloadId.value,job.destination,"video/mp4","MP4",record.stagingReference,job.status.name,job.error?.message,record.createdAtMs,System.currentTimeMillis())
    }
    override fun close() { if(::writer.isInitialized) writer.close() }
}

internal fun detailsJson(d: TitleDetails): String = JSONObject().put("description",d.description).put("provenance",d.provenance)
    .put("entities",JSONArray(d.entities.map { JSONObject().put("kind",it.kind.name).put("key",it.key).put("label",it.label) }))
    .put("sources",JSONArray(d.sources.map { JSONObject().put("id",it.id.value).put("provider",it.provider).put("url",it.originalUrl).put("kind",it.kind.name)
        .put("externalId",it.externalId).put("adapterKey",it.adapterKey).put("headerPolicyKey",it.headerPolicyKey).put("provenance",it.provenance) })).toString()
internal fun retainedDetails(summary: TitleSummary,json: String?): TitleDetails {
    val j=json?.let(::JSONObject) ?: JSONObject()
    fun JSONObject.nullable(key: String)=if(isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    val sources=j.optJSONArray("sources") ?: JSONArray();val entities=j.optJSONArray("entities") ?: JSONArray()
    return TitleDetails(summary,j.nullable("description"),(0 until sources.length()).map { i -> sources.getJSONObject(i).let { s ->
        SourceHandle(summary.id,SourceId(s.getString("id")),s.getString("provider"),s.getString("url"),StoredSourceKind.valueOf(s.getString("kind")),s.nullable("externalId"),s.nullable("adapterKey"),s.nullable("headerPolicyKey"),s.nullable("provenance"))
    } },(0 until entities.length()).map { i -> entities.getJSONObject(i).let { EntityRef(EntityKind.valueOf(it.getString("kind")),it.getString("key"),it.getString("label")) } },j.nullable("provenance"))
}
