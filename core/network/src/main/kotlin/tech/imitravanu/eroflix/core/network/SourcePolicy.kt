package tech.imitravanu.eroflix.core.network

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tech.imitravanu.eroflix.model.*
import java.io.IOException
import java.util.Locale

/** One immutable policy per selected source; exact hosts, no global credentials. */
class SourcePolicy(allowedHosts: Set<String>,scopes: List<RequestScope> = emptyList(),
    val allowFixtureLoopback: Boolean = false,val maxRedirects: Int = 4) {
    val allowedHosts=allowedHosts.map { it.lowercase(Locale.ROOT) }.toSet()
    val scopes=scopes.map { RequestScope(it.allowedHosts.map { h -> h.lowercase(Locale.ROOT) }.toSet(),it.headers.toMap(),it.cookies.toMap()) }
    init {
        require(this.allowedHosts.isNotEmpty() && maxRedirects in 0..8)
        require(this.scopes.all { this.allowedHosts.containsAll(it.allowedHosts) })
        this.scopes.forEach { s ->
            require(s.headers.keys.none { it.equals("Host",true) || it.equals("Cookie",true) || it.equals("Range",true) || it.equals("If-Range",true) })
            require(s.headers.all { (k,v) -> k.matches(Regex("[A-Za-z0-9-]+")) && '\r' !in v && '\n' !in v })
            require(s.cookies.all { (k,v) -> k.matches(Regex("[A-Za-z0-9_-]+")) && v.none { it in "\r\n;" } })
        }
    }
    private val wildcardHosts = setOf("ahcdn.com", "nv7s.com", "archive.org", "tizam.cc", "tizam.org")
    fun accepts(raw: String): Boolean {
        val u=raw.toHttpUrlOrNull() ?: return false
        return accepts(u)
    }
    fun accepts(u: HttpUrl): Boolean {
        val host = u.host.lowercase(Locale.ROOT)
        val hostMatch = allowedHosts.any { allowed ->
            host == allowed || (allowed in wildcardHosts && host.endsWith(".$allowed"))
        }
        return hostMatch && u.username.isEmpty() && u.password.isEmpty() &&
            (u.isHttps && u.port==443 || allowFixtureLoopback && u.scheme=="http" && u.host in setOf("127.0.0.1","localhost","::1"))
    }
    fun headers(u: HttpUrl): Map<String,String> = buildMap {
        put("User-Agent","EroflixMobile/0.2")
        scopes.filter { scope ->
            scope.allowedHosts.any { allowed -> u.host.equals(allowed, true) || u.host.endsWith(".$allowed", true) }
        }.forEach { scope ->
            putAll(scope.headers)
            if(scope.cookies.isNotEmpty()) put("Cookie",scope.cookies.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }
}
class SourcePolicyException(message: String,val blockedHost: String? = null): IOException(message)

/** Match the final provider origin after a permitted redirect; never forward query tokens. */
internal fun SourcePolicy.withOriginReferer(page: String): SourcePolicy {
    val u=page.toHttpUrlOrNull() ?: throw SourcePolicyException("Invalid provider page.")
    require(accepts(u))
    val referer=u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    return SourcePolicy(allowedHosts,scopes+RequestScope(allowedHosts,mapOf("Referer" to referer)),allowFixtureLoopback,maxRedirects)
}

/** Rebuild headers at each validated hop; never carry credentials across hosts. */
class ScopedSourceInterceptor(private val policy: SourcePolicy): Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original=chain.request()
        if(original.method !in setOf("GET","HEAD")) throw SourcePolicyException("Only source reads are supported.")
        var url=original.url
        repeat(policy.maxRedirects+1) { hop ->
            if(!policy.accepts(url)) throw SourcePolicyException("Source destination is outside its compiled policy.",url.host)
            val request=Request.Builder().url(url).method(original.method,null)
            // Only transport controls supplied by our media layer survive a redirect.
            for(name in listOf("Range","If-Range","If-None-Match","If-Modified-Since","Accept","Accept-Encoding"))
                original.header(name)?.let { request.header(name,it) }
            policy.headers(url).forEach { (name,value) -> request.header(name,value) }
            val response=chain.proceed(request.build())
            if(response.code !in setOf(301,302,303,307,308)) return response
            val target=response.header("Location")?.let { url.resolve(it) }
            response.close()
            if(target==null || !policy.accepts(target)) throw SourcePolicyException("Source redirect is not permitted.",target?.host)
            if(hop==policy.maxRedirects) throw SourcePolicyException("Source redirected too many times.")
            url=target
        }
        throw SourcePolicyException("Source redirect limit exceeded.")
    }
}

object ProviderPolicies {
    val protectedHosts=setOf(
        "7530883417654311.vip","fxcdndx.top","mp4hls.click","cyoucyou.top",
        "pk112qr.vip","u2f85d3o9x.one","d1erix.vip","bubababaskababa.space",
        "iotokia.click","pinkclk.click","classicpornmagazines.click","t0mbiks.click",
        "112dxfx.sbs","43754389.click","digeek.click","9799112.vip","magazinecdn.click"
    )
    val embedFamilies: Map<String,Set<String>> = mapOf(
        "tubepornclassic" to setOf("tubepornclassic.com","www.tubepornclassic.com","ahcdn.com","nv7s.com"),
        "eporner" to setOf("eporner.com","www.eporner.com"),
        "tizam" to setOf("abv.tizam.org","adv.tizam.org","tizam.org","www.tizam.org","tizam.cc"),
        "fullerotic" to setOf("fulleroticmovies.net","www.fulleroticmovies.net"),
        "porntrex" to setOf("porntrex.com","www.porntrex.com"),
        "archive" to setOf("archive.org"),
        "pornissimo" to setOf("pornissimo.to"),
        "allclassic" to setOf("allclassic.org","theclassicporn.com"),
        "erogarga" to setOf("erogarga.com"),
        "mp4porn" to setOf("mp4-porn.com"),
        "pinkueiga" to setOf("pinkueiga.com","cat3movie.org"),
        "vintagepornfun" to setOf("vintagepornfun.com"),
        "watcherotic" to setOf("watcherotic.com")
    )
    // Resource hosts observed in the providers' actual player DOM/public source fields.
    val embedResourceHosts: Map<String,Set<String>> = mapOf(
        "tubepornclassic" to setOf("tpcah1.nv7s.com","ahcdn.com"),
        "tizam" to setOf("video1.tizam.cc","video2.tizam.cc","video3.tizam.cc","video4.tizam.cc","tizam.cc"),
        "eporner" to setOf("i.eporner.com","static-eu-cdn.eporner.com","eporner.com")
    )
    fun family(host: String): String? {
        val lower = host.lowercase(Locale.ROOT)
        return embedFamilies.entries.firstOrNull { (_, domains) ->
            domains.any { d -> lower == d || lower.endsWith(".$d") }
        }?.key
    }
    fun forSource(source: SourceHandle): SourcePolicy? {
        val url=source.originalUrl.toHttpUrlOrNull() ?: return null
        if(!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if(url.host in protectedHosts) return SourcePolicy(protectedHosts,listOf(RequestScope(protectedHosts,mapOf("Referer" to "https://erogarga.com/"))))
        val family=family(url.host) ?: embedResourceHosts.entries.firstOrNull { (_, hosts) -> hosts.any { url.host == it || url.host.endsWith(".$it") } }?.key
        val hosts=family?.let { embedFamilies.getValue(it)+embedResourceHosts[it].orEmpty() } ?: setOf(url.host)
        val referer = when (family) {
            "tizam" -> "https://adv.tizam.org/"
            "tubepornclassic" -> "https://www.tubepornclassic.com/"
            "eporner" -> "https://www.eporner.com/"
            "erogarga" -> "https://erogarga.com/"
            else -> "${url.scheme}://${url.host}/"
        }
        return SourcePolicy(hosts,listOf(RequestScope(hosts,mapOf("Referer" to referer))))
    }
}

