package tech.imitravanu.eroflix.core.data

import android.system.Os
import android.system.OsConstants
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import tech.imitravanu.eroflix.model.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

fun parseCatalogManifest(text: String): CatalogManifest {
    val j=JSONObject(text)
    return CatalogManifest(j.getInt("formatVersion"),j.getInt("catalogSchemaVersion"),GenerationId(j.getString("generationId")),
        j.getLong("createdAt"),j.getLong("titleCount"),j.getLong("sourceCount"),j.getLong("minAppVersionCode"),j.getString("artifactUrl"),
        j.getString("compression"),j.getLong("compressedBytes"),j.getLong("uncompressedBytes"),j.getString("compressedSha256"),j.getString("uncompressedSha256"))
}
private fun CatalogManifest.json()=JSONObject().put("formatVersion",formatVersion).put("catalogSchemaVersion",schemaVersion)
    .put("generationId",generationId.value).put("createdAt",createdAtEpochMs).put("titleCount",titleCount).put("sourceCount",sourceCount)
    .put("minAppVersionCode",minAppVersionCode).put("artifactUrl",artifactUrl).put("compression",compression).put("compressedBytes",compressedBytes)
    .put("uncompressedBytes",uncompressedBytes).put("compressedSha256",compressedSha256).put("uncompressedSha256",uncompressedSha256).toString()

/** Readers lease immutable generations. Only admission drains during pointer publication. */
class GenerationStorage(private val root: File, private val appVersion: Long = 1,
    private val failurePoint: (String)->Unit = {}, private val availableBytes: ()->Long = { root.usableSpace }) : CatalogGenerationStore {
    private val driver=BundledSQLiteDriver()
    private val updater=Mutex()
    private val admission=Mutex()
    private val pool=Semaphore(3)
    private var leases=0
    private var drained=CompletableDeferred<Unit>().apply { complete(Unit) }
    private val mutable=MutableStateFlow<GenerationId?>(null)
    override val active: StateFlow<GenerationId?> = mutable.asStateFlow()
    val progress=MutableStateFlow<Pair<Long,Long>?>(null)
    private val pointer get()=File(root,"current.json")
    private var previous: GenerationId?=null
    private fun directory(id: GenerationId): File {
        requireData(id.value.matches(Regex("[A-Za-z0-9_-]{1,96}")),ErrorCode.INCOMPATIBLE_CATALOG,"Invalid generation identity.")
        return File(root,id.value)
    }
    fun database(id: GenerationId)=File(directory(id),"catalog.db")
    private fun manifest(id: GenerationId)=parseCatalogManifest(File(directory(id),"manifest.json").readText())
    suspend fun initialize()=withContext(Dispatchers.IO) {
        root.mkdirs()
        root.listFiles()?.filter { it.name.startsWith("stage-") || it.name=="current.tmp" }?.forEach { it.deleteRecursively() }
        if(pointer.exists()) {
            val p=runCatching { JSONObject(pointer.readText()) }.getOrNull()
            val candidates=listOfNotNull(p?.optString("current")?.takeIf { it.isNotEmpty() },p?.optString("previous")?.takeIf { it.isNotEmpty() })
            for(candidate in candidates) {
                val id=GenerationId(candidate)
                if(runCatching { validate(database(id),manifest(id),false) }.isSuccess) {
                    mutable.value=id
                    previous=candidates.firstOrNull { it!=candidate }?.let(::GenerationId)
                    if(candidate!=candidates.first()) publish(id,null)
                    return@withContext
                }
            }
            throw DataFailure(AppError(ErrorCode.CORRUPT_CATALOG,"The installed catalogs could not be opened. Retry provisioning.",true))
        }
    }
    suspend fun needsBundledGeneration(bundle: CatalogManifest): Boolean = withContext(Dispatchers.IO) {
        val id=active.value
        id==null || manifest(id).createdAtEpochMs<bundle.createdAtEpochMs
    }
    suspend fun cleanObsolete(): Outcome<Unit> = outcome { withContext(Dispatchers.IO) { updater.withLock { admission.withLock {
        drained.await()
        val keep=setOfNotNull(active.value?.value,previous?.value)
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith("stage-") && it.name !in keep }?.forEach { it.deleteRecursively() }
        syncDir(root)
    } } } }
    private fun syncDir(file: File) {
        val fd=Os.open(file.absolutePath,OsConstants.O_RDONLY,0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun writeSynced(file: File, text: String) { FileOutputStream(file).use { it.write(text.toByteArray());it.fd.sync() } }
    private fun sha(file: File): String {
        val md=MessageDigest.getInstance("SHA-256");file.inputStream().use { input -> val b=ByteArray(256*1024);while(true) { val n=input.read(b);if(n<0) break;md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun checkManifest(m: CatalogManifest) {
        directory(m.generationId)
        requireData(m.formatVersion==1 && m.schemaVersion==1 && m.minAppVersionCode<=appVersion && m.compression=="gzip",
            ErrorCode.INCOMPATIBLE_CATALOG,"This catalog requires a different app version.")
        requireData(m.uncompressedBytes in 1..(2L*1024*1024*1024) && m.compressedBytes in 1..(1024L*1024*1024) && m.titleCount>0 && m.sourceCount>=0 &&
            listOf(m.compressedSha256,m.uncompressedSha256).all { it.matches(Regex("[a-f0-9]{64}")) },ErrorCode.CORRUPT_CATALOG,"Invalid catalog sizes or digests.")
    }
    private fun validate(file: File,m: CatalogManifest,full: Boolean) {
        checkManifest(m)
        requireData(file.length()==m.uncompressedBytes,ErrorCode.CORRUPT_CATALOG,"Catalog size does not match its manifest.")
        if(full) requireData(sha(file)==m.uncompressedSha256,ErrorCode.CORRUPT_CATALOG,"Catalog checksum does not match.")
        driver.open(file.absolutePath,1).use { c ->
            requireData(c.long("PRAGMA application_id")==1163021388L && c.long("PRAGMA user_version")==1L,ErrorCode.INCOMPATIBLE_CATALOG,"Invalid catalog schema.")
            val meta=c.rows("SELECT key,value FROM catalog_meta") { it.getText(0) to it.getText(1) }.toMap()
            requireData(meta["generationId"]==m.generationId.value && meta["titleCount"]==m.titleCount.toString() && meta["sourceCount"]==m.sourceCount.toString(),ErrorCode.CORRUPT_CATALOG,"Catalog metadata does not match.")
            if(full) {
                requireData(c.rows("PRAGMA integrity_check") { it.getText(0) }==listOf("ok") && c.rows("PRAGMA foreign_key_check") { 1 }.isEmpty(),ErrorCode.CORRUPT_CATALOG,"Catalog integrity validation failed.")
                requireData(c.long("SELECT count(*) FROM titles")==m.titleCount && c.long("SELECT count(*) FROM sources")==m.sourceCount,ErrorCode.CORRUPT_CATALOG,"Catalog counts do not match.")
                requireData(c.long("SELECT count(*) FROM sqlite_master WHERE name IN ('user_state','personal_meta','retained_titles')")==0L,ErrorCode.CORRUPT_CATALOG,"Personal data is not permitted in a catalog.")
            }
            c.rows("SELECT rowid FROM titles_fts WHERE titles_fts MATCH '\"catalogprobe\"' LIMIT 1") { it.getLong(0) }
        }
    }
    override suspend fun stage(manifest: CatalogManifest,localArtifact: String): Outcome<GenerationId> = outcome { withContext(Dispatchers.IO) { updater.withLock {
        checkManifest(manifest);root.mkdirs();val final=directory(manifest.generationId)
        if(final.exists()) {
            val old=manifest(manifest.generationId)
            requireData(old.uncompressedSha256==manifest.uncompressedSha256,ErrorCode.CORRUPT_CATALOG,"A different catalog already uses this generation identity.")
            validate(database(manifest.generationId),manifest,true)
            return@withLock manifest.generationId
        }
        val input=File(localArtifact)
        requireData(input.length()==manifest.compressedBytes && sha(input)==manifest.compressedSha256,ErrorCode.CORRUPT_CATALOG,"Compressed catalog verification failed.")
        failurePoint("compressed-verified")
        requireData(availableBytes()>=manifest.uncompressedBytes+manifest.compressedBytes+1024L*1024*1024,ErrorCode.STORAGE_FULL,"Free at least 1 GiB plus catalog staging space.")
        val temp=File(root,"stage-${UUID.randomUUID()}");temp.mkdirs()
        try {
            val file=File(temp,"catalog.db");var count=0L;progress.value=0L to manifest.uncompressedBytes
            GZIPInputStream(input.inputStream()).use { gz -> FileOutputStream(file).use { out ->
                val b=ByteArray(256*1024)
                while(true) { currentCoroutineContext().ensureActive();val n=gz.read(b);if(n<0) break;count+=n
                    requireData(count<=manifest.uncompressedBytes,ErrorCode.CORRUPT_CATALOG,"Catalog expanded beyond its declared size.")
                    out.write(b,0,n);progress.value=count to manifest.uncompressedBytes
                };out.fd.sync()
            } }
            failurePoint("extracted");validate(file,manifest,true);failurePoint("validated")
            writeSynced(File(temp,"manifest.json"),manifest.json());syncDir(temp);failurePoint("files-synced")
            check(temp.renameTo(final));syncDir(root);failurePoint("generation-renamed")
            manifest.generationId
        } finally { temp.deleteRecursively();progress.value=null }
    } } }
    private fun publish(id: GenerationId,old: GenerationId?) {
        val staged=File(root,"current.tmp")
        writeSynced(staged,JSONObject().put("current",id.value).put("previous",old?.value ?: "").toString())
        failurePoint("pointer-synced")
        Os.rename(staged.absolutePath,pointer.absolutePath);syncDir(root)
    }
    override suspend fun activate(generation: GenerationId): Outcome<Unit> = outcome { withContext(Dispatchers.IO) { updater.withLock { admission.withLock {
        drained.await();validate(database(generation),manifest(generation),false);failurePoint("reopened")
        if(mutable.value!=generation) {
            val old=mutable.value
            // Publication is a small non-cancellable commit, after all validation.
            withContext(NonCancellable) { publish(generation,old);previous=old;mutable.value=generation }
            failurePoint("pointer-published")
        }
    } } } }
    override suspend fun rollback(): Outcome<GenerationId> {
        val old=previous ?: return Outcome.Failure(AppError(ErrorCode.MISSING_CATALOG,"There is no previous catalog."))
        return when(val r=activate(old)) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.Success(old) }
    }
    override suspend fun retainedGenerations(): List<GenerationId> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory && File(it,"manifest.json").isFile }?.map { GenerationId(it.name) }.orEmpty()
    }
    internal suspend fun <T> read(personal: File,block: (androidx.sqlite.SQLiteConnection,GenerationId)->T): T = withContext(Dispatchers.IO) { pool.withPermit {
        val id=admission.withLock {
            val value=mutable.value ?: throw DataFailure(AppError(ErrorCode.MISSING_CATALOG,"Catalog preparation has not completed.",true))
            synchronized(this@GenerationStorage) { if(leases++==0) drained=CompletableDeferred() };value
        }
        try {
            driver.open(personal.absolutePath,1 or 64).use { c ->
                c.exec("PRAGMA busy_timeout=5000");c.exec("PRAGMA foreign_keys=ON")
                // URI mode=ro is enforced by SQLITE_OPEN_URI (64) on this reader.
                val uri=database(id).toURI().toASCIIString()+"?mode=ro&immutable=1"
                c.exec("ATTACH DATABASE ? AS catalog",uri);c.exec("PRAGMA query_only=ON");c.exec("BEGIN")
                try { block(c,id) } finally { c.exec("ROLLBACK") }
            }
        } finally { synchronized(this@GenerationStorage) { if(--leases==0) drained.complete(Unit) } }
    } }
}
