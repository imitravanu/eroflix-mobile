package tech.imitravanu.eroflix.core.network

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates signed metadata; no transport or production trust key is configured here. */
internal object SignedUpdateManifest {
    data class Trust(val channel: String,val keys: Map<String,ByteArray>,val allowedHosts: Set<String>)
    data class Floor(val sequence: Long,val digest: String)
    data class Catalog(val sequence: Long,val digest: String,val generation: String,val artifactUrl: String,
        val compressedBytes: Long,val uncompressedBytes: Long,val compressedSha256: String,val uncompressedSha256: String)
    data class App(val versionCode: Long,val versionName: String,val minSdk: Long,val abi: String,
        val artifactUrl: String,val bytes: Long,val sha256: String,val signingCertificateSha256: String)

    /** Authenticates metadata only. The downloaded APK and Android installer still require verification. */
    fun app(bytes: ByteArray,signature: ByteArray,keyId: String,trust: Trust,packageName: String,
        currentVersion: Long,deviceSdk: Long,deviceAbis: Set<String>,installedCertificateSha256: String): App {
        require(bytes.size in 1..65536 && signature.size==64) { "Invalid signed metadata size" }
        val key=trust.keys[keyId] ?: throw IllegalArgumentException("Unknown signing key")
        val publicKey=KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key))
        require(Signature.getInstance("Ed25519").run { initVerify(publicKey);update(bytes);verify(signature) }) { "Invalid signature" }
        val json=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        fun string(name: String)=json[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing $name")
        fun number(name: String)=json[name]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Invalid $name")
        require(number("formatVersion")==1L && string("channel")==trust.channel && string("keyId")==keyId) { "Wrong update format, channel or key" }
        require(string("packageName")==packageName) { "Wrong application package" }
        val code=number("versionCode");val name=string("versionName");val sdk=number("minSdk");val abi=string("abi")
        require(code>currentVersion && code<=Int.MAX_VALUE && name.length in 1..128 && sdk in 1..deviceSdk && abi in deviceAbis) { "Incompatible app update" }
        val length=number("artifactBytes");val digest=string("sha256");val certificate=string("signingCertificateSha256")
        require(length in 1..2_147_483_648L && digest.matches(Regex("[0-9a-f]{64}")) &&
            certificate.matches(Regex("[0-9a-f]{64}")) && certificate==installedCertificateSha256) { "Invalid artifact or signing identity" }
        val url=string("artifactUrl").toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid artifact URL")
        require(url.isHttps && url.port==443 && url.host in trust.allowedHosts && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null) { "Untrusted artifact URL" }
        return App(code,name,sdk,abi,url.toString(),length,digest,certificate)
    }

    fun catalog(bytes: ByteArray,signature: ByteArray,keyId: String,trust: Trust,floor: Floor?,appVersion: Long): Catalog {
        require(bytes.size in 1..65536 && signature.size==64) { "Invalid signed metadata size" }
        val key=trust.keys[keyId] ?: throw IllegalArgumentException("Unknown signing key")
        val publicKey=KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key))
        require(Signature.getInstance("Ed25519").run { initVerify(publicKey);update(bytes);verify(signature) }) { "Invalid signature" }
        val json=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        fun string(name: String)=json[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing $name")
        fun number(name: String)=json[name]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Invalid $name")
        require(number("formatVersion")==1L && number("catalogSchemaVersion")==1L) { "Unsupported catalog format" }
        require(string("channel")==trust.channel && string("keyId")==keyId) { "Wrong update channel or key" }
        val sequence=number("catalogSequence")
        require(sequence>0 && number("minAppVersionCode") in 1..appVersion) { "Incompatible catalog" }
        require(number("createdAt")>0 && number("titleCount")>0 && number("sourceCount")>=0 &&
            string("kind")=="full" && string("compression")=="gzip") { "Invalid catalog metadata" }
        val generation=string("generationId")
        // Match GenerationStorage's supported IDs and size bounds before transfer.
        require(generation.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid generation" }
        val compressed=number("compressedBytes");val uncompressed=number("uncompressedBytes")
        require(compressed in 1..1_073_741_824L && uncompressed in 1..2_147_483_648L) { "Invalid artifact size" }
        val compressedHash=string("compressedSha256");val uncompressedHash=string("uncompressedSha256")
        require(compressedHash.matches(Regex("[0-9a-f]{64}")) && uncompressedHash.matches(Regex("[0-9a-f]{64}"))) { "Invalid digest" }
        val url=string("artifactUrl").toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid artifact URL")
        require(url.isHttps && url.port==443 && url.host in trust.allowedHosts && url.username.isEmpty() && url.password.isEmpty() && url.fragment==null) { "Untrusted artifact URL" }
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        if(floor!=null) require(sequence>floor.sequence || sequence==floor.sequence && digest==floor.digest) { "Catalog rollback or changed sequence" }
        return Catalog(sequence,digest,generation,url.toString(),compressed,uncompressed,compressedHash,uncompressedHash)
    }
}
