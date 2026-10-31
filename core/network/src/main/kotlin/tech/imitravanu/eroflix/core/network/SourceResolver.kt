package tech.imitravanu.eroflix.core.network

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tech.imitravanu.eroflix.model.*
import java.io.IOException
import java.time.Instant

/** Transport/manifest validation is separate from real device decode/playback evidence. */
class CatalogSourceResolver(private val http: SourceHttp = SourceHttp(),
    private val policyFor: (SourceHandle)->SourcePolicy? = ProviderPolicies::forSource,
    private val clockMs: ()->Long = System::currentTimeMillis,
    private val embedFamily: (String)->String? = ProviderPolicies::family,
    private val onBlockedHost: (String)->Unit = {}): SourceResolver {
    override suspend fun resolve(source: SourceHandle,purpose: ResolvePurpose): Resolution = withContext(Dispatchers.IO) { resolveOffMain(source,purpose) }
    private suspend fun resolveOffMain(source: SourceHandle,purpose: ResolvePurpose): Resolution {
        val policy=policyFor(source) ?: return unavailable(ErrorCode.UNSUPPORTED,"This source has no supported HTTPS request policy.")
        return try { withTimeoutOrNull(25_000) {
            val expiry=expiryMs(source.originalUrl)
            if(expiry!=null && expiry<=clockMs()) return@withTimeoutOrNull unavailable(ErrorCode.EXPIRED,"The source URL has expired. A fresh provider URL is required.",true)
            val protected=source.originalUrl.toHttpUrlOrNull()?.host in ProviderPolicies.protectedHosts
            if(source.kind==StoredSourceKind.EMBED && !protected) resolveEmbed(source,purpose,policy,expiry)
            else resolveNative(source,source.originalUrl,policy,expiry,purpose)
        } ?: unavailable(ErrorCode.NETWORK,"Source resolution timed out.",true) } catch(e: CancellationException) { throw e }
        catch(e: SourcePolicyException) { e.blockedHost?.let(onBlockedHost);unavailable(ErrorCode.PROVIDER_CHANGED,"A source request left its permitted host policy.") }
        catch(e: IOException) { unavailable(ErrorCode.NETWORK,"The source could not be reached securely.",true) }
        catch(e: IllegalArgumentException) { unavailable(ErrorCode.PROVIDER_CHANGED,"The source returned an invalid URL or manifest.") }
    }
    private suspend fun resolveEmbed(source: SourceHandle,purpose: ResolvePurpose,policy: SourcePolicy,expiry: Long?): Resolution {
        val host=source.originalUrl.toHttpUrlOrNull()?.host.orEmpty()
        val family=embedFamily(host) ?: return unavailable(ErrorCode.UNSUPPORTED,"This embed provider has no compiled adapter.")
        if(family=="tubepornclassic") {
            val lookup=TubePublicAdapter(http).lookup(source.originalUrl,policy)
            if(lookup.finalFailure) return Resolution.Unavailable(requireNotNull(lookup.error))
            var failure: Resolution.Unavailable?=null
            for(url in lookup.urls) {
                val resolved=resolveNative(source,url,policy,expiry,purpose)
                if(resolved is Resolution.NativeMedia) return resolved
                if(resolved is Resolution.Unavailable) failure=resolved
            }
            if(purpose==ResolvePurpose.DOWNLOAD) return failure ?: Resolution.Unavailable(lookup.error ?: AppError(ErrorCode.UNSUPPORTED,"This provider returned no supported public media."))
        }
        val page=http.fetch(source.originalUrl,policy,256*1024,false)
        statusError(page.status)?.let { return Resolution.Unavailable(it) }
        if(!page.text.trimStart().startsWith("<") && page.header("Content-Type")?.contains("html",true)!=true)
            return unavailable(ErrorCode.PROVIDER_CHANGED,"The provider no longer returned an embed page.")
        // PornTrex's embed can expose preview files; those are not the full selected title.
        val candidates=if(family=="porntrex") emptyList() else PublicSourceTags.extract(page.text)+PublicPlayerConfig.extract(family,page.text)
        val pageUrl=page.url.toHttpUrlOrNull()!!
        val mediaPolicy=if(family in ProviderPolicies.embedFamilies) policy.withOriginReferer(page.url) else policy
        for(url in candidates.mapNotNull { pageUrl.resolve(it)?.toString() }.filter { mediaPolicy.accepts(it) }.distinct().take(3)) {
            val native=resolveNative(source,url,mediaPolicy,expiry,purpose)
            if(native is Resolution.NativeMedia) return native
        }
        if(purpose==ResolvePurpose.DOWNLOAD) return unavailable(ErrorCode.UNSUPPORTED,"This provider currently requires its web player; native downloads are unavailable.")
        return Resolution.WebEmbed(page.url,"embed:$family:v1","Provider web player. Native quality, native gestures, offline downloads and export are unavailable. Playback still requires device verification.")
    }
    private suspend fun resolveNative(source: SourceHandle,url: String,policy: SourcePolicy,expiry: Long?,purpose: ResolvePurpose): Resolution {
        val resourceExpiry=expiryMs(url) ?: expiry
        if(resourceExpiry!=null && resourceExpiry<=clockMs()) return unavailable(ErrorCode.EXPIRED,"The resolved media URL has expired.",true)
        var probe=http.fetch(url,policy,8192,true)
        statusError(probe.status)?.let { return Resolution.Unavailable(it) }
        if(probe.status==206 && probe.header("Content-Range")?.matches(Regex("bytes 0-\\d+/\\d+"))!=true)
            return unavailable(ErrorCode.PROVIDER_CHANGED,"The source returned an inconsistent byte range.")
        val mime=sniffMedia(probe)
        if(mime==null) return unavailable(ErrorCode.PROVIDER_CHANGED,"The source returned HTML or an unrecognized media format.")
        var live=false
        val tracks=if(mime=="application/x-mpegURL") {
            if(probe.truncated) {
                probe=http.fetch(probe.url,policy,512*1024,false)
                statusError(probe.status)?.let { return Resolution.Unavailable(it) }
                if(sniffMedia(probe)!="application/x-mpegURL") return unavailable(ErrorCode.PROVIDER_CHANGED,"The manifest changed while resolving the source.")
            }
            if(probe.truncated) return unavailable(ErrorCode.UNSUPPORTED,"The HLS manifest exceeds the supported size limit.")
            val hls=HlsManifest.parse(probe.url,probe.text)
            live=hls.live
            if(hls.unsupportedEncryption) return unavailable(ErrorCode.UNSUPPORTED,"This HLS encryption or DRM mode is not supported.")
            if(hls.resources.any { !policy.accepts(it) }) return unavailable(ErrorCode.PROVIDER_CHANGED,"The playlist references a host outside its source policy.")
            if(hls.variants.size>16) return unavailable(ErrorCode.UNSUPPORTED,"The playlist has too many variants for bounded resolution.")
            // Validate a bounded representative child manifest; subrequests use the same policy.
            hls.variants.firstOrNull()?.let { variant ->
                val child=http.fetch(variant.uri,policy,512*1024,false)
                statusError(child.status)?.let { return Resolution.Unavailable(it) }
                if(child.truncated || !child.text.trimStart().startsWith("#EXTM3U")) return unavailable(ErrorCode.PROVIDER_CHANGED,"The HLS variant is not a supported playlist.")
                val parsed=HlsManifest.parse(child.url,child.text);live=parsed.live
                if(parsed.variants.isNotEmpty() || parsed.unsupportedEncryption || parsed.resources.any { !policy.accepts(it) }) return unavailable(ErrorCode.UNSUPPORTED,"Nested, encrypted or out-of-policy HLS variants are not supported.")
            }
            hls.variants.map { MediaTrack(stableDigest(it.uri),it.label,"application/x-mpegURL",it.height,bitrate=it.bandwidth) }
        } else emptyList()
        if(live && purpose==ResolvePurpose.DOWNLOAD) return unavailable(ErrorCode.UNSUPPORTED,"Live HLS is not a finite offline download.")
        val validators=validators(probe)
        return Resolution.NativeMedia(probe.url,mime,listOf(RequestScope(policy.allowedHosts))+policy.scopes,expiryMs(probe.url) ?: resourceExpiry,
            stableDigest(source.id.value,probe.url,validators.etag.orEmpty(),validators.lastModified.orEmpty(),validators.lengthBytes?.toString().orEmpty()),
            source.provenance,tracks,MediaCapabilities(nativePlayback=true,downloadable=!live,exportable=false),validators)
    }
}
internal fun unavailable(code: ErrorCode,text: String,retry: Boolean=false)=Resolution.Unavailable(AppError(code,text,retry))
internal fun statusError(status: Int): AppError? = when(status) {
    200,206 -> null
    401 -> AppError(ErrorCode.AUTH_REQUIRED,"The provider requires authentication.")
    403,410 -> AppError(ErrorCode.EXPIRED,"The source has expired or access is denied. Refresh the provider source.",true)
    404 -> AppError(ErrorCode.NOT_FOUND,"The source is no longer available.")
    429 -> AppError(ErrorCode.NETWORK,"The provider is rate limiting requests.",true)
    in 500..599 -> AppError(ErrorCode.NETWORK,"The provider is temporarily unavailable.",true)
    else -> AppError(ErrorCode.PROVIDER_CHANGED,"Unexpected source response.")
}
internal fun sniffMedia(p: ProbeResponse): String? {
    val b=p.bytes;val content=p.header("Content-Type").orEmpty().substringBefore(';').lowercase()
    if(content.contains("html") || p.text.trimStart().take(32).lowercase().startsWith("<!doctype html") || p.text.trimStart().startsWith("<html",true)) return null
    if(p.text.trimStart('\uFEFF',' ','\r','\n','\t').startsWith("#EXTM3U")) return "application/x-mpegURL"
    if(b.size>=12 && b.copyOfRange(4,8).contentEquals("ftyp".toByteArray())) return "video/mp4"
    if(b.size>=4 && b.take(4).map { it.toInt() and 255 }==listOf(0x1a,0x45,0xdf,0xa3)) return "video/webm"
    if(b.size>=377 && b[0]==0x47.toByte() && b[188]==0x47.toByte() && b[376]==0x47.toByte()) return "video/mp2t"
    return null
}
internal fun validators(p: ProbeResponse): ResourceValidators {
    val range=p.header("Content-Range")?.let { Regex("bytes 0-\\d+/(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
    val length=range ?: if(p.status==200) p.header("Content-Length")?.toLongOrNull() else null
    return ResourceValidators(p.header("ETag"),p.header("Last-Modified"),length,p.header("Accept-Ranges")?.equals("bytes",true)==true || p.status==206 && range!=null)
}
fun canResumeResource(previous: ResourceValidators,next: ResourceValidators): Boolean {
    if(!next.acceptsRanges || previous.lengthBytes==null || previous.lengthBytes!=next.lengthBytes) return false
    val etag=previous.etag
    if(etag!=null && !etag.startsWith("W/")) return etag==next.etag
    // A timestamp and byte length can collide; neither proves byte identity.
    return false
}
fun expiryMs(url: String): Long? {
    val u=url.toHttpUrlOrNull() ?: return null
    for(key in listOf("expires","expire","exp")) {
        val raw=u.queryParameter(key) ?: continue
        raw.toLongOrNull()?.let { if(it>0) return if(it>=1_000_000_000_000L) it else runCatching { Math.multiplyExact(it,1000) }.getOrNull() }
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
    }
    return null
}
/** A bounded, data-only extraction; no script evaluation or obfuscation bypass. */
