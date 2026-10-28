package tech.imitravanu.eroflix.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tech.imitravanu.eroflix.model.*

/** Observes ordinary media requests; blob URLs/DRM licenses are never treated as files. */
object StreamDetection {
    fun mime(url: String,hint: String?=null): String? {
        val path=url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return null
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            hint?.substringBefore(';') in setOf("video/mp4","video/webm","application/x-mpegURL","application/vnd.apple.mpegurl","application/dash+xml") -> hint!!.substringBefore(';')
            else -> null
        }
    }
    fun publicMediaUrl(url: String): Boolean {
        val u=url.toHttpUrlOrNull() ?: return false
        val host=u.host.lowercase()
        return u.isHttps && u.port==443 && u.username.isEmpty() && u.password.isEmpty() &&
            host!="localhost" && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.contains(':') && !host.matches(Regex("[0-9.]+"))
    }
    fun candidate(url: String,headers: Map<String,String>,page: String,hint: String?=null): DetectedStream? {
        val mime=mime(url,hint) ?: return null
        if(!publicMediaUrl(url)) return null
        val u=url.toHttpUrlOrNull() ?: return null
        val safe=headers.filter { (name,value) -> name.lowercase() in setOf("user-agent","referer","origin","authorization") && '\r' !in value && '\n' !in value }.toMutableMap()
        if(safe.keys.none { it.equals("Referer",true) }) safe["Referer"]=page
        val label=when(mime) { "application/x-mpegURL","application/vnd.apple.mpegurl" -> "HLS stream";"application/dash+xml" -> "DASH stream";else -> "Video file" }
        return DetectedStream(url,mime,"$label · ${u.host}",listOf(RequestScope(setOf(u.host),safe)))
    }
}
