package tech.imitravanu.eroflix.core.media
import org.junit.Assert.*
import org.junit.Test
import tech.imitravanu.eroflix.model.*

class DownloadPolicyTest {
    @Test fun exactSourceAndStreamKeysDeduplicateRegardlessOfSelectionOrder() {
        val source=SourceId("source-one")
        val keys=listOf(Triple(0,0,1),Triple(0,1,0),Triple(1,0,0))
        assertEquals(downloadJobIdentity(source,keys),downloadJobIdentity(source,keys.reversed()))
        assertNotEquals(downloadJobIdentity(source,keys),downloadJobIdentity(SourceId("source-two"),keys))
        assertNotEquals(downloadJobIdentity(source,keys),downloadJobIdentity(source,keys.dropLast(1)))
        assertNotEquals(downloadJobIdentity(source,listOf(Triple(0,0,1))),downloadJobIdentity(source,listOf(Triple(0,1,1))))
    }
    @Test fun reservationsKeepOneGiBFloorWith64BitSizesAndNoOverflow() {
        val gib=1024L*1024*1024
        assertTrue(hasDownloadSpace(12*gib,listOf(5*gib),5*gib))
        assertFalse(hasDownloadSpace(10*gib,listOf(5*gib),5*gib))
        assertFalse(hasDownloadSpace(Long.MAX_VALUE,listOf(Long.MAX_VALUE),1))
        assertFalse(hasDownloadSpace(gib-1,emptyList(),0))
        assertFalse(hasDownloadSpace(4*gib,listOf(-1),0))
        assertTrue(hasDownloadSpace(gib+1,emptyList(),1))
    }
    @Test fun wifiMeansActualTransportNotUnmeteredOrVpnByItself() {
        assertTrue(downloadNetworkAllowed(true,true,true))
        assertFalse(downloadNetworkAllowed(true,false,true))
        assertTrue(downloadNetworkAllowed(true,false,false))
        assertFalse(downloadNetworkAllowed(false,true,false))
    }
    @Test fun onlyStrongEqualValidatedResourcesCanResumePartialBytes() {
        fun media(v: ResourceValidators?)=Resolution.NativeMedia("https://fixture.invalid/v.mp4","video/mp4",emptyList(),null,"identity",null,emptyList(),MediaCapabilities(true,true,false),v)
        val strong=ResourceValidators("\"etag\"",null,5L*1024*1024*1024,true)
        assertTrue(resumeIdentityMatches(media(strong),media(strong)))
        assertFalse(resumeIdentityMatches(media(strong),media(strong.copy(etag="W/\"etag\""))))
        assertFalse(resumeIdentityMatches(media(strong),media(strong.copy(lengthBytes=strong.lengthBytes!!+1))))
        assertFalse(resumeIdentityMatches(media(strong),media(strong.copy(acceptsRanges=false))))
        assertFalse(resumeIdentityMatches(media(null),media(null)))
        assertFalse(resumeIdentityMatches(media(strong),media(strong).copy(identityFingerprint="changed")))
    }
}
