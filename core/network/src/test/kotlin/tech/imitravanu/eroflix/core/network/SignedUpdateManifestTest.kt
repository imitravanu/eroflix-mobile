package tech.imitravanu.eroflix.core.network

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class SignedUpdateManifestTest {
    private val pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val trust=SignedUpdateManifest.Trust("stable",mapOf("test-key" to pair.public.encoded),setOf("updates.example"))
    private fun manifest(seq: Long=3,url: String="https://updates.example/catalog.gz") = """{"formatVersion":1,"catalogSchemaVersion":1,"channel":"stable","keyId":"test-key","catalogSequence":$seq,"minAppVersionCode":1,"createdAt":1700000000000,"kind":"full","titleCount":10,"sourceCount":20,"compression":"gzip","generationId":"fixture-v3","compressedBytes":100,"uncompressedBytes":500,"compressedSha256":"${"a".repeat(64)}","uncompressedSha256":"${"b".repeat(64)}","artifactUrl":"$url"}""".toByteArray()
    private fun sign(bytes: ByteArray)=Signature.getInstance("Ed25519").run { initSign(pair.private);update(bytes);sign() }
    private fun verify(bytes: ByteArray,signature: ByteArray=sign(bytes),floor: SignedUpdateManifest.Floor?=null)=SignedUpdateManifest.catalog(bytes,signature,"test-key",trust,floor,1)
    @Test fun validSignedBytesAndExactRetryAreAccepted() {
        val bytes=manifest();val result=verify(bytes)
        assertEquals(3,result.sequence);assertEquals("fixture-v3",result.generation)
        assertEquals(result,verify(bytes,floor=SignedUpdateManifest.Floor(3,result.digest)))
    }
    @Test fun changedBytesWrongKeyAndRollbackAreRejected() {
        val original=manifest();val signature=sign(original)
        assertTrue(runCatching { verify(manifest(4),signature) }.isFailure)
        assertTrue(runCatching { SignedUpdateManifest.catalog(original,signature,"unknown",trust,null,1) }.isFailure)
        assertTrue(runCatching { verify(original,floor=SignedUpdateManifest.Floor(4,"a".repeat(64))) }.isFailure)
        assertTrue(runCatching { verify(original,floor=SignedUpdateManifest.Floor(3,"a".repeat(64))) }.isFailure)
    }
    @Test fun signedButUntrustedUrlAndIncompatibleAppAreRejected() {
        assertTrue(runCatching { verify(manifest(url="http://updates.example/catalog.gz")) }.isFailure)
        assertTrue(runCatching { verify(manifest(url="https://other.example/catalog.gz")) }.isFailure)
        assertTrue(runCatching { verify(manifest(url="https://updates.example:444/catalog.gz")) }.isFailure)
        val bytes=manifest();assertTrue(runCatching { SignedUpdateManifest.catalog(bytes,sign(bytes),"test-key",trust,null,0) }.isFailure)
    }
    @Test fun catalogMetadataFitsGenerationStorageLimits() {
        fun changed(from: String,to: String)=manifest().toString(Charsets.UTF_8).replace(from,to).toByteArray()
        assertTrue(runCatching { verify(changed("fixture-v3","fixture.v3")) }.isFailure)
        assertTrue(runCatching { verify(changed("fixture-v3","a".repeat(97))) }.isFailure)
        assertTrue(runCatching { verify(changed("\"compressedBytes\":100","\"compressedBytes\":1073741825")) }.isFailure)
        assertTrue(runCatching { verify(changed("\"uncompressedBytes\":500","\"uncompressedBytes\":2147483649")) }.isFailure)
    }
    private fun appManifest(version: Long=2,packageName: String="tech.imitravanu.eroflix",sdk: Long=37,
        abi: String="arm64-v8a",url: String="https://updates.example/app.apk",certificate: String="c".repeat(64)) =
        """{"formatVersion":1,"channel":"stable","keyId":"test-key","packageName":"$packageName","versionCode":$version,"versionName":"0.2","minSdk":$sdk,"abi":"$abi","artifactUrl":"$url","artifactBytes":100,"sha256":"${"d".repeat(64)}","signingCertificateSha256":"$certificate"}""".toByteArray()
    private fun verifyApp(bytes: ByteArray, signature: ByteArray=sign(bytes)) = SignedUpdateManifest.app(
        bytes,signature,"test-key",trust,"tech.imitravanu.eroflix",1,37,setOf("arm64-v8a"),"c".repeat(64))
    @Test fun appMetadataRequiresValidSignatureAndMatchingIdentity() {
        val valid=appManifest();assertEquals(2,verifyApp(valid).versionCode)
        assertTrue(runCatching { verifyApp(appManifest(version=3),sign(valid)) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(packageName="other.package")) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(certificate="e".repeat(64))) }.isFailure)
    }
    @Test fun appMetadataRejectsDowngradeIncompatibleDeviceAndUntrustedUrl() {
        assertTrue(runCatching { verifyApp(appManifest(version=1)) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(sdk=38)) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(abi="x86")) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(url="https://other.example/app.apk")) }.isFailure)
        assertTrue(runCatching { verifyApp(appManifest(url="http://updates.example/app.apk")) }.isFailure)
    }
}
