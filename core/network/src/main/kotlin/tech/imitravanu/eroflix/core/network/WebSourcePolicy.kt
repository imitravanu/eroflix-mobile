package tech.imitravanu.eroflix.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WebSourcePolicy(val entryUrl: String,val contextLabel: String,hosts: Set<String>,navigationHosts: Set<String>? = null,
    val playerContainerSelector: String = "body") {
    val requestPolicy=SourcePolicy(hosts)
    private val entry=entryUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid web source")
    private val navigationHosts=navigationHosts?.toSet() ?: setOf(entry.host)
    init { require(requestPolicy.accepts(entry));require(requestPolicy.allowedHosts.containsAll(this.navigationHosts)) }
    // The selected embed is a user entry, not a link from another WebView page.
    // Providers that require a Referer must receive it on this first request too.
    val initialRequestHeaders: Map<String,String> = mapOf("Referer" to entry.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
    fun topLevel(url: String): Boolean = requestPolicy.accepts(url) && url.toHttpUrlOrNull()?.let { it.host in navigationHosts && it.encodedPath==entry.encodedPath }==true
    fun resource(url: String): Boolean {
        val resource=url.toHttpUrlOrNull() ?: return false
        if(requestPolicy.accepts(resource)) return true
        // The provider rotates numbered regional media nodes between requests.
        // Restrict this to its observed CDN naming shape and owned domain, with no credentials.
        return ProviderPolicies.family(entry.host)=="eporner" &&
            Regex("(?:dash|vid)-s[1-9][0-9]?-[ns]50-fr-cdn\\.eporner\\.com").matches(resource.host) &&
            resource.isHttps && resource.port==443 && resource.username.isEmpty() && resource.password.isEmpty()
    }
    companion object {
        fun forEntry(url: String): WebSourcePolicy? {
            val host=url.toHttpUrlOrNull()?.host ?: return null
            val family=ProviderPolicies.family(host) ?: return null
            val player=when(family) { "eporner" -> "#EPvideo";"fullerotic","porntrex" -> "#kt_player";"tubepornclassic" -> ".videoplayer";else -> "body" }
            return runCatching { WebSourcePolicy(url,host,ProviderPolicies.embedFamilies.getValue(family)+ProviderPolicies.embedResourceHosts[family].orEmpty(),ProviderPolicies.embedFamilies.getValue(family),player) }.getOrNull()
        }
    }
}
