package tech.imitravanu.eroflix.core.network

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tech.imitravanu.eroflix.model.*
import java.util.Base64

internal data class TubeLookup(val urls: List<String> = emptyList(),val error: AppError? = null,val finalFailure: Boolean = false)

/** The same public JSON routes and non-cryptographic transport alphabet used by the provider player. */
internal class TubePublicAdapter(private val http: SourceHttp) {
    suspend fun lookup(entry: String,policy: SourcePolicy): TubeLookup {
        val page=entry.toHttpUrlOrNull() ?: return bad("Invalid provider entry.",true)
        val id=Regex("^/embed/([0-9]{1,12})/?$").matchEntire(page.encodedPath)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it>0 }
            ?: return bad("This provider entry has no supported video identity.",true)
        val metadataUrl=page.resolve("/api/json/video/86400/${id/1000000*1000000}/${id/1000*1000}/$id.json")!!.toString()
        val metadata=http.fetch(metadataUrl,policy,128*1024,false)
        statusError(metadata.status)?.let { return TubeLookup(error=it,finalFailure=it.code in setOf(ErrorCode.AUTH_REQUIRED,ErrorCode.NOT_FOUND)) }
        if(metadata.truncated) return bad("Provider metadata exceeded its size limit.")
        val root=runCatching { Json.parseToJsonElement(metadata.text) as? JsonObject }.getOrNull() ?: return bad("Provider metadata is not supported JSON.")
        val video=root["video"] as? JsonObject ?: return bad("Provider metadata no longer identifies a video.")
        if(video["video_id"].text()!=id.toString()) return bad("Provider metadata identifies a different video.",true)
        if(video["status_id"].text()=="5") return TubeLookup(error=AppError(ErrorCode.NOT_FOUND,"The provider removed this video."),finalFailure=true)
        if(video["status_id"].text()!="1") return TubeLookup(error=AppError(ErrorCode.UNSUPPORTED,"The provider has not made this video available."),finalFailure=true)
        if(video["is_private"].text()=="1") return TubeLookup(error=AppError(ErrorCode.AUTH_REQUIRED,"The provider requires access to this private video."),finalFailure=true)
        if(video["is_private"].text()!="0") return bad("The provider did not establish public video access.")
        val mediaUrl=page.resolve("/api/videofile.php")!!.newBuilder().addQueryParameter("video_id",id.toString()).addQueryParameter("lifetime","8640000").build().toString()
        val media=http.fetch(mediaUrl,policy,128*1024,false)
        statusError(media.status)?.let { return TubeLookup(error=it,finalFailure=it.code==ErrorCode.AUTH_REQUIRED) }
        if(media.truncated) return bad("Provider media metadata exceeded its size limit.")
        val rows=runCatching { Json.parseToJsonElement(media.text) as? JsonArray }.getOrNull() ?: return bad("Provider media metadata is not a supported list.")
        if(rows.size !in 1..16) return bad("Provider media metadata has an unsupported number of sources.")
        val progressive=rows.mapNotNull { row ->
            val encoded=(row as? JsonObject)?.get("video_url").text() ?: return@mapNotNull null
            val decoded=TubeUrlEncoding.decode(encoded) ?: return@mapNotNull null
            page.resolve(decoded)?.toString()?.takeIf { policy.accepts(it) }
        }.distinct().take(2)
        if(progressive.isEmpty()) return TubeLookup(error=AppError(ErrorCode.UNSUPPORTED,"No public provider media URL fits its compiled policy."))
        // The player offers HLS for videos longer than 30 minutes; keep the original progressive fallback.
        val adaptive=if(durationSeconds(video["duration"].text())>1800) progressive.mapNotNull { url ->
            val parsed=url.toHttpUrlOrNull() ?: return@mapNotNull null
            if(parsed.queryParameter("f")!=null) null else parsed.newBuilder().addQueryParameter("f","video.m3u8").build().toString()
        } else emptyList()
        return TubeLookup((adaptive+progressive).distinct().take(3))
    }
    private fun bad(message: String,final: Boolean=false)=TubeLookup(error=AppError(ErrorCode.PROVIDER_CHANGED,message),finalFailure=final)
    private fun durationSeconds(raw: String?): Long {
        val parts=raw?.split(':')?.map { it.toLongOrNull() ?: return 0L } ?: return 0L
        if(parts.size !in 1..3 || parts.any { it<0 || it>100000 }) return 0
        return parts.fold(0L) { seconds,value -> seconds*60+value }
    }
}
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Strict custom Base64 + legacy percent decoding, not script evaluation or an account/challenge bypass. */
internal object TubeUrlEncoding {
    private const val ALPHABET="АВСDЕFGHIJKLМNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,~"
    private const val STANDARD="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    fun decode(encoded: String): String? {
        if(encoded.isEmpty() || encoded.length>8192 || encoded.length%4!=0) return null
        val canonical=StringBuilder(encoded.length)
        for(char in encoded) { val index=ALPHABET.indexOf(char);if(index<0) return null;canonical.append(STANDARD[index]) }
        val raw=runCatching { String(Base64.getDecoder().decode(canonical.toString()),Charsets.ISO_8859_1) }.getOrNull() ?: return null
        val result=StringBuilder();var i=0
        while(i<raw.length) {
            if(raw[i]=='%' && i+1<raw.length) {
                val unicode=raw[i+1]=='u';val count=if(unicode) 4 else 2;val start=i+if(unicode) 2 else 1
                val code=raw.substring(start,minOf(raw.length,start+count)).takeIf { it.length==count }?.toIntOrNull(16)
                if(code!=null) { result.append(code.toChar());i=start+count;continue }
            }
            result.append(raw[i++])
            if(result.length>4096) return null
        }
        return result.toString().takeIf { it.length<=4096 && it.none(Char::isISOControl) && (it.startsWith("/") || it.startsWith("https://")) }
    }
}
