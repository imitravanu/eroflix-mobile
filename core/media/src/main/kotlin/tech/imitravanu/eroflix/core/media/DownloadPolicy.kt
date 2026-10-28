package tech.imitravanu.eroflix.core.media

import tech.imitravanu.eroflix.model.*

const val DOWNLOAD_DISK_FLOOR=1024L*1024*1024
const val UNKNOWN_DOWNLOAD_RESERVATION=512L*1024*1024
fun downloadJobIdentity(source: SourceId,keys: List<Triple<Int,Int,Int>>)=JobId(stableDigest(source.value,
    keys.sortedWith(compareBy({ it.first },{ it.second },{ it.third })).joinToString(";") { "${it.first}:${it.second}:${it.third}" }))
fun hasDownloadSpace(free: Long,reservations: List<Long>,additional: Long): Boolean {
    if(free<DOWNLOAD_DISK_FLOOR || additional<0) return false
    var remaining=free-DOWNLOAD_DISK_FLOOR
    for(bytes in reservations+additional) { if(bytes<0 || bytes>remaining) return false;remaining-=bytes }
    return true
}
fun resumeIdentityMatches(old: Resolution.NativeMedia,fresh: Resolution.NativeMedia): Boolean {
    val a=old.validators;val b=fresh.validators
    return old.identityFingerprint==fresh.identityFingerprint && a!=null && b!=null &&
        a.etag?.let { it.isNotBlank() && !it.startsWith("W/",true) && it==b.etag }==true &&
        a.lengthBytes!=null && a.lengthBytes==b.lengthBytes && a.acceptsRanges && b.acceptsRanges
}
/** Actual Wi-Fi transport is required; a VPN flag or unmetered cellular alone is insufficient. */
fun downloadNetworkAllowed(connected: Boolean,wifiTransport: Boolean,wifiOnly: Boolean)=connected && (!wifiOnly || wifiTransport)
