package tech.imitravanu.eroflix.core.data

import org.json.*
import tech.imitravanu.eroflix.model.*

internal fun PersistedDownload.snapshotJson(): String {
    val title=intent.title;val source=intent.source;val media=native
    return JSONObject().put("format",1).put("streamKeys",JSONArray(streamKeys.map { JSONArray(listOf(it.period,it.group,it.stream)) })).put("title",JSONObject().put("id",title.id.value).put("title",title.title).put("year",title.year)
        .put("duration",title.durationMs).put("poster",title.posterUrl))
        .put("source",JSONObject().put("id",source.id.value).put("provider",source.provider).put("url",source.originalUrl)
            .put("kind",source.kind.name).put("external",source.externalId).put("adapter",source.adapterKey).put("headers",source.headerPolicyKey).put("provenance",source.provenance))
        .put("native",JSONObject().put("uri",media.uri).put("mime",media.mimeType).put("expiry",media.expiresAtEpochMs)
            .put("identity",media.identityFingerprint).put("provenance",media.provenance).put("estimate",estimatedBytes)
            .put("tracks",JSONArray(media.tracks.map { JSONObject().put("id",it.id).put("label",it.label).put("mime",it.mimeType).put("height",it.height).put("language",it.language).put("bitrate",it.bitrate) }))
            .put("scopes",JSONArray(media.requestScopes.map { JSONObject().put("hosts",JSONArray(it.allowedHosts.sorted())).put("headers",JSONObject(it.headers)).put("cookies",JSONObject(it.cookies)) }))
            .put("validators",media.validators?.let { JSONObject().put("etag",it.etag).put("modified",it.lastModified).put("length",it.lengthBytes).put("ranges",it.acceptsRanges) }))
        .put("downloadable",media.capabilities.downloadable).put("exportable",media.capabilities.exportable).toString()
}
internal fun List<DownloadTrackRef>.trackJson()=JSONArray(map { JSONObject().put("period",it.period).put("group",it.group).put("track",it.track).put("type",it.type)
    .put("id",it.formatId).put("label",it.label).put("height",it.height).put("language",it.language) }).toString()
internal fun restoredDownload(id: String,snapshot: String,tracks: String,order: Long,desired: String,reason: String?): PersistedDownload {
    val root=JSONObject(snapshot);requireData(root.optInt("format",1)==1,ErrorCode.INCOMPATIBLE_CATALOG,"Download intent format requires a newer app.");val t=root.getJSONObject("title");val s=root.getJSONObject("source");val m=root.getJSONObject("native")
    fun JSONObject.text(key: String)=if(isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    fun JSONObject.number(key: String)=if(isNull(key)) null else getLong(key)
    fun JSONObject.strings()=keys().asSequence().associateWith { getString(it) }
    val title=TitleSummary(TitleId(t.getString("id")),t.getString("title"),t.number("year")?.toInt(),t.number("duration"),t.text("poster"))
    val source=SourceHandle(title.id,SourceId(s.getString("id")),s.getString("provider"),s.getString("url"),StoredSourceKind.valueOf(s.getString("kind")),s.text("external"),s.text("adapter"),s.text("headers"),s.text("provenance"))
    val scopes=m.getJSONArray("scopes").let { array -> (0 until array.length()).map { array.getJSONObject(it).let { scope ->
        val hosts=scope.getJSONArray("hosts");RequestScope((0 until hosts.length()).map { hosts.getString(it) }.toSet(),scope.getJSONObject("headers").strings(),scope.getJSONObject("cookies").strings())
    } } }
    val validators=m.optJSONObject("validators")?.let { ResourceValidators(it.text("etag"),it.text("modified"),it.number("length"),it.optBoolean("ranges")) }
    val native=Resolution.NativeMedia(m.getString("uri"),m.text("mime"),scopes,m.number("expiry"),m.getString("identity"),m.text("provenance"),m.optJSONArray("tracks")?.let { array -> (0 until array.length()).map { array.getJSONObject(it).let { track -> MediaTrack(track.getString("id"),track.getString("label"),track.text("mime"),track.number("height")?.toInt(),track.text("language"),track.number("bitrate")?.toInt()) } } }.orEmpty(),MediaCapabilities(true,root.optBoolean("downloadable"),root.optBoolean("exportable")),validators)
    val array=JSONArray(tracks);val selected=(0 until array.length()).map { array.getJSONObject(it).let { track -> DownloadTrackRef(track.getInt("period"),track.getInt("group"),track.getInt("track"),track.getInt("type"),track.text("id"),track.getString("label"),track.number("height")?.toInt(),track.text("language")) } }
    return PersistedDownload(JobId(id),DownloadIntent(title,source,TrackSelection(selected.mapNotNull { it.formatId }.toSet())),selected,native,order,DownloadDesired.valueOf(desired),reason,m.number("estimate"),root.optJSONArray("streamKeys")?.let { keys -> (0 until keys.length()).map { keys.getJSONArray(it).let { key -> DownloadStreamRef(key.getInt(0),key.getInt(1),key.getInt(2)) } } }.orEmpty())
}
