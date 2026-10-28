package tech.imitravanu.eroflix.core.data

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tech.imitravanu.eroflix.model.*
import java.util.BitSet

internal data class SqlPredicate(val sql: String,val args: List<Any?>)
internal object CatalogSql {
    fun predicate(q: CatalogQuery,except: String? = null,visibleOnly: Boolean = true): SqlPredicate {
        requireData(q.search.length<=256,ErrorCode.INVALID_QUERY,"Search supports at most 256 characters.")
        val parts=mutableListOf<String>()
        if(visibleOnly) parts+="t.id NOT IN (SELECT u.title_id FROM main.user_state u WHERE u.hidden=1 AND u.title_id IS NOT NULL)"
        val args=mutableListOf<Any?>()
        val terms=Regex("[\\p{L}\\p{N}]+").findAll(fold(q.search)).map { it.value }.toList()
        requireData(terms.size<=16,ErrorCode.INVALID_QUERY,"Search supports at most 16 words.")
        val latin=terms.filter { term -> term.codePoints().noneMatch(::isCjk) }
        if(latin.isNotEmpty()) {
            parts+="t.row_id IN (SELECT rowid FROM catalog.titles_fts WHERE titles_fts MATCH ?)"
            args+=latin.joinToString(" AND ") { "\"$it\"*" }
        }
        terms.filter { term -> term.codePoints().anyMatch(::isCjk) }.forEach { term ->
            val cps=term.codePoints().toArray();val first=cps.indexOfFirst(::isCjk)
            val length=if(first+1<cps.size && isCjk(cps[first+1])) 2 else 1
            val gram=String(cps,first,length)
            parts+="t.row_id IN (SELECT title_row FROM catalog.cjk_grams WHERE gram=?)";args+=gram
            parts+="t.row_id IN (SELECT row_id FROM catalog.search_documents WHERE title LIKE ? OR entity_text LIKE ? OR description LIKE ?)"
            repeat(3) { args+="%$term%" }
        }
        fun relation(group: String,table: String,values: Set<FacetValue>) {
            if(group==except || values.isEmpty()) return
            requireData(values.size<=100,ErrorCode.INVALID_QUERY,"Too many selected filters.")
            val known=values.filterIsInstance<FacetValue.Known>().map { it.key }
            val choices=mutableListOf<String>()
            if(known.isNotEmpty()) { choices+="t.row_id IN (SELECT title_row FROM catalog.title_$table WHERE key IN (${known.joinToString(",") { "?" }}))";args.addAll(known) }
            if(FacetValue.Unknown in values) {
                if(table=="providers") choices+="EXISTS (SELECT 1 FROM catalog.title_providers r WHERE r.title_row=t.row_id AND r.key='@unknown')"
                else choices+="NOT EXISTS (SELECT 1 FROM catalog.title_$table r WHERE r.title_row=t.row_id)"
            }
            parts+=choices.joinToString(" OR ","(",")")
        }
        relation("provider","providers",q.providers);relation("country","countries",q.countries);relation("studio","studios",q.studios)
        if(except!="decade" && q.decades.isNotEmpty()) {
            requireData(q.decades.size<=100,ErrorCode.INVALID_QUERY,"Too many decades.")
            val choices=q.decades.map { value -> when(value) {
                FacetValue.Unknown -> "t.year IS NULL"
                is FacetValue.Known -> {
                    val y=value.key.toIntOrNull();requireData(y!=null && y in 0..9990 && y%10==0,ErrorCode.INVALID_QUERY,"Invalid decade.")
                    args+=y;args+=y!!+9;"t.year BETWEEN ? AND ?"
                }
            } };parts+=choices.joinToString(" OR ","(",")")
        }
        q.entity?.let { e ->
            when(e.kind) {
                EntityKind.DECADE -> { val year=e.key.toIntOrNull();requireData(year!=null,ErrorCode.INVALID_QUERY,"Invalid decade.");parts+="t.year BETWEEN ? AND ?";args+=year;args+=year!!+9 }
                else -> {
                    val table=when(e.kind) { EntityKind.PERFORMER -> "performers";EntityKind.STUDIO -> "studios";EntityKind.SERIES -> "series";EntityKind.COUNTRY -> "countries";else -> error("unreachable") }
                    parts+="t.row_id IN (SELECT title_row FROM catalog.title_$table WHERE key=?)";args+=e.key
                }
            }
        }
        if(q.duration.onlyUnknown) parts+="t.duration_ms IS NULL"
        else if(q.duration!=DurationFilter()) {
        val duration=mutableListOf<String>()
        if(q.duration.includeUnknown) duration+="t.duration_ms IS NULL"
        val known=mutableListOf("t.duration_ms IS NOT NULL")
        q.duration.minMs?.let { known+="t.duration_ms>=?";args+=it };q.duration.maxMs?.let { known+="t.duration_ms<=?";args+=it }
        duration+=known.joinToString(" AND ","(",")");parts+=duration.joinToString(" OR ","(",")")
        }
        return SqlPredicate(parts.joinToString(" AND ").ifEmpty { "1" },args)
    }
    fun sort(q: CatalogQuery)=when(q.sort) { CatalogSort.TITLE -> "t.sort_title";CatalogSort.YEAR_NEWEST -> "coalesce(t.year,-1)";CatalogSort.YEAR_OLDEST -> "coalesce(t.year,99999)" }
}

class CatalogStorage(private val generations: GenerationStorage,private val personal: PersonalStorage) : CatalogRepository {
    override val activeGeneration=generations.active
    override suspend fun metadata(): Outcome<CatalogMetadata> = outcome {
        generations.read(personal.file) { c, generation ->
            CatalogMetadata(generation, c.long("SELECT CAST(value AS INTEGER) FROM catalog.catalog_meta WHERE key='titleCount'"), c.long("SELECT CAST(value AS INTEGER) FROM catalog.catalog_meta WHERE key='sourceCount'"))
        }
    }
    override suspend fun count(query: CatalogQuery): Outcome<CatalogCount> = outcome {
        generations.read(personal.file) { c, generation ->
            val predicate = CatalogSql.predicate(query)
            CatalogCount(QueryStamp(query.key(), generation, c.long("SELECT revision FROM personal_meta WHERE id=1")),
                c.long("SELECT count(*) FROM catalog.titles t WHERE ${predicate.sql}", predicate.args))
        }
    }
    override suspend fun query(query: CatalogQuery): Outcome<Page<TitleSummary>> = outcome {
        currentCoroutineContext().ensureActive()
        generations.read(personal.file) { c,generation ->
            val stamp=QueryStamp(query.key(),generation,c.long("SELECT revision FROM personal_meta WHERE id=1"))
            requireData(query.cursor?.let { it.stamp==stamp } != false,ErrorCode.INVALID_QUERY,"Results changed. Restart from the first page.")
            val where=CatalogSql.predicate(query);val sort=CatalogSql.sort(query);val descending=query.sort==CatalogSort.YEAR_NEWEST
            val args=where.args.toMutableList();var seek=""
            query.cursor?.let {
                val value: Any=if(query.sort==CatalogSort.TITLE) it.lastSortValue else it.lastSortValue.toLongOrNull() ?: throw DataFailure(AppError(ErrorCode.INVALID_QUERY,"Invalid page cursor."))
                seek=" AND ($sort ${if(descending) "<" else ">"} ? OR ($sort=? AND t.id>?))";args+=value;args+=value;args+=it.lastTitleId.value
            }
            args+=query.limit+1
            val result=c.rows("SELECT t.id,t.title,t.year,t.duration_ms,t.poster_url,$sort FROM catalog.titles t WHERE ${where.sql}$seek ORDER BY $sort ${if(descending) "DESC" else "ASC"},t.id LIMIT ?",args) { it.summary() to it.getText(5) }
            val page=result.take(query.limit)
            Page(page.map { it.first },if(result.size>query.limit) page.last().let { PageCursor(stamp,it.second,it.first.id) } else null,stamp)
        }
    }
    override suspend fun facets(query: CatalogQuery): Outcome<CatalogFacets> = outcome {
        generations.read(personal.file) { c,generation ->
            val accelerators=c.rows("SELECT name FROM catalog.sqlite_master WHERE name IN ('facet_cross_counts','ix_titles_facets','facet_members','facet_bitmaps')") { it.getText(0) }.toSet()
            val indexHint=if("ix_titles_facets" in accelerators) "INDEXED BY ix_titles_facets" else ""
            val selections=listOf("provider" to query.providers,"country" to query.countries,"decade" to query.decades,"studio" to query.studios).filter { it.second.isNotEmpty() }
            val simpleScope=if("facet_cross_counts" in accelerators && query.search.isBlank() && query.entity==null && query.duration==DurationFilter() && selections.size==1 && selections.single().second.size==1) selections.single().let { it.first to it.second.single() } else null
            val sharedCounts=if(selections.isEmpty() && (query.search.isNotBlank() || query.entity!=null || query.duration!=DurationFilter())) {
                val p=CatalogSql.predicate(query)
                val groups=listOf("provider" to "providers","country" to "countries","studio" to "studios")
                // scope row IDs and each title/key relation are unique; aggregate before label lookup.
                val statements=groups.map { (group,table) -> "SELECT '$group',coalesce(a.key,'@unknown'),coalesce(v.label,'Unknown'),a.n FROM (SELECT r.key,count(*) n FROM scope s LEFT JOIN catalog.title_$table r ON r.title_row=s.row_id GROUP BY r.key) a LEFT JOIN catalog.$table v ON v.key=a.key" } +
                    "SELECT 'decade',coalesce(CAST(s.year/10*10 AS TEXT),'@unknown'),coalesce(CAST(s.year/10*10 AS TEXT),'Unknown'),count(*) FROM scope s GROUP BY s.year/10"
                val aggregates=if("facet_members" in accelerators)
                    "SELECT o.facet,o.key,o.label,a.n FROM (SELECT m.option_id,count(*) n FROM scope s JOIN catalog.facet_members m ON m.title_row=s.row_id GROUP BY m.option_id) a JOIN catalog.facet_options o ON o.id=a.option_id"
                    else statements.joinToString(" UNION ALL ")
                val useBitmaps="facet_bitmaps" in accelerators && c.long("SELECT coalesce(sum(length(bits)),0) FROM catalog.facet_bitmaps") in 1..(32L*1024*1024) && c.long("SELECT max(row_id) FROM catalog.titles")<=2_000_000
                if(useBitmaps) {
                    // Only a bounded membership mask stays in data memory; no title/hidden list enters UI.
                    val visible=BitSet()
                    c.prepare("SELECT t.row_id FROM catalog.titles t $indexHint WHERE ${p.sql}").use { s ->
                        s.bind(p.args)
                        while(s.step()) { val row=s.getLong(0);requireData(row in 1..2_000_000,ErrorCode.INCOMPATIBLE_CATALOG,"Catalog row range exceeds the supported bitmap limit.");visible.set(row.toInt()) }
                    }
                    if(visible.isEmpty) emptyMap() else if(visible.cardinality()<=16_384) {
                        // Sparse scopes are cheaper to aggregate directly than reading every bitmap.
                        c.rows("WITH scope AS MATERIALIZED (SELECT t.row_id,t.year FROM catalog.titles t $indexHint WHERE ${p.sql}) "+aggregates,p.args) {
                            it.getText(0) to Triple(it.getText(1),it.getText(2),it.getLong(3))
                        }.groupBy({it.first},{it.second})
                    } else {
                        val words=visible.toLongArray()
                        c.rows("SELECT o.facet,o.key,o.label,b.bits FROM catalog.facet_bitmaps b JOIN catalog.facet_options o ON o.id=b.option_id") { s ->
                            s.getText(0) to Triple(s.getText(1),s.getText(2),countMembership(s.getBlob(3),words))
                        }.filter { it.second.third>0 }.groupBy({it.first},{it.second})
                    }
                } else c.rows("WITH scope AS MATERIALIZED (SELECT t.row_id,t.year FROM catalog.titles t $indexHint WHERE ${p.sql}) "+aggregates,p.args) {
                    it.getText(0) to Triple(it.getText(1),it.getText(2),it.getLong(3))
                }.groupBy({it.first},{it.second})
            } else null
            fun options(group: String,table: String,selected: Set<FacetValue>): List<FacetOption> {
                val p=CatalogSql.predicate(query,group)
                val key=if(group=="decade") "CASE WHEN t.year IS NULL THEN NULL ELSE CAST(t.year/10*10 AS TEXT) END" else "r.key"
                val join=if(group=="decade") "" else "LEFT JOIN catalog.title_$table r ON r.title_row=t.row_id LEFT JOIN catalog.$table v ON v.key=r.key"
                val label=if(group=="decade") key else "v.label"
                val unconstrained=query.search.isBlank() && query.entity==null && query.duration==DurationFilter() &&
                    (group=="provider" || query.providers.isEmpty()) && (group=="country" || query.countries.isEmpty()) &&
                    (group=="decade" || query.decades.isEmpty()) && (group=="studio" || query.studios.isEmpty())
                val rows=if(sharedCounts!=null) sharedCounts[group].orEmpty() else if(unconstrained || simpleScope!=null) {
                    val hiddenPredicate=CatalogSql.predicate(query,group,visibleOnly=false)
                    val hidden=c.rows("SELECT $key,count(DISTINCT t.row_id) FROM main.user_state u JOIN catalog.titles t ON t.id=u.title_id $join WHERE u.hidden=1 AND ${hiddenPredicate.sql} GROUP BY $key",hiddenPredicate.args) {
                        (it.textOrNull(0) ?: "@unknown") to it.getLong(1)
                    }.toMap()
                    val totalSql=if(unconstrained) "SELECT key,label,title_count FROM catalog.facet_counts WHERE facet=?" else "SELECT key,label,title_count FROM catalog.facet_cross_counts WHERE scope_facet=? AND scope_key=? AND facet=?"
                    val totalArgs=if(unconstrained) listOf(group) else listOf(simpleScope!!.first,(simpleScope.second as? FacetValue.Known)?.key ?: "@unknown",group)
                    c.rows(totalSql,totalArgs) {
                        Triple(it.getText(0),it.getText(1),it.getLong(2)-(hidden[it.getText(0)] ?: 0))
                    }.filter { it.third>0 }
                } else c.rows("SELECT $key,$label,count(DISTINCT t.row_id) FROM catalog.titles t $indexHint $join WHERE ${p.sql} GROUP BY $key",p.args) {
                    Triple(it.textOrNull(0),it.textOrNull(1),it.getLong(2))
                }
                val counts=rows.map { row ->
                    val k=row.first;val value=if(k==null || k=="@unknown") FacetValue.Unknown else FacetValue.Known(k)
                    FacetOption(value,if(value==FacetValue.Unknown) "Unknown" else row.second ?: k.orEmpty(),row.third,value in selected)
                }
                return (counts+selected.filter { s -> counts.none { it.value==s } }.map { s ->
                    val keyValue=(s as? FacetValue.Known)?.key
                    val knownLabel=if(group!="decade" && keyValue!=null) c.rows("SELECT label FROM catalog.$table WHERE key=?",listOf(keyValue)) { it.getText(0) }.firstOrNull() else keyValue
                    FacetOption(s,knownLabel ?: "Unknown",0,true)
                }).sortedWith(compareByDescending<FacetOption> { it.titleCount }.thenBy { it.label })
            }
            val p=CatalogSql.predicate(query.copy(duration=DurationFilter()))
            CatalogFacets(options("provider","providers",query.providers),options("country","countries",query.countries),options("decade","",query.decades),options("studio","studios",query.studios),
                c.long("SELECT CAST(value AS INTEGER) FROM catalog.catalog_meta WHERE key='knownDurationCount'")>0 && c.long("SELECT EXISTS(SELECT 1 FROM catalog.titles t WHERE ${p.sql} AND t.duration_ms IS NOT NULL)",p.args)!=0L,
                QueryStamp(query.key(),generation,c.long("SELECT revision FROM personal_meta WHERE id=1")))
        }
    }
    override suspend fun details(id: TitleId): Outcome<TitleDetails> = outcome {
        generations.read(personal.file) { c,_ -> readDetails(c,id) ?: c.rows("SELECT title_id,title,year,duration_ms,poster_url,details_json FROM retained_titles WHERE title_id=?",listOf(id.value)) { retainedDetails(it.summary(),it.textOrNull(5)) }.firstOrNull()
            ?: throw DataFailure(AppError(ErrorCode.NOT_FOUND,"This title is no longer available.")) }
    }
}
internal fun readDetails(c: SQLiteConnection,id: TitleId): TitleDetails? {
    val base=c.rows("SELECT id,title,year,duration_ms,poster_url,description,provenance,row_id FROM catalog.titles WHERE id=?",listOf(id.value)) {
        Triple(it.summary(),it.textOrNull(5) to it.textOrNull(6),it.getLong(7))
    }.firstOrNull() ?: return null
    val sources=c.rows("SELECT id,provider,original_url,kind,external_id,adapter_key,header_policy_key,provenance FROM catalog.sources WHERE title_row=? ORDER BY id",listOf(base.third)) {
        SourceHandle(id,SourceId(it.getText(0)),it.getText(1),it.getText(2),StoredSourceKind.valueOf(it.getText(3)),it.textOrNull(4),it.textOrNull(5),it.textOrNull(6),it.textOrNull(7))
    }
    val entities=listOf("performers" to EntityKind.PERFORMER,"studios" to EntityKind.STUDIO,"series" to EntityKind.SERIES,"countries" to EntityKind.COUNTRY).flatMap { (table,kind) ->
        c.rows("SELECT e.key,e.label FROM catalog.$table e JOIN catalog.title_$table r ON r.key=e.key WHERE r.title_row=? ORDER BY e.key",listOf(base.third)) { EntityRef(kind,it.getText(0),it.getText(1)) }
    }
    return TitleDetails(base.first,base.second.first,sources,entities,base.second.second)
}
