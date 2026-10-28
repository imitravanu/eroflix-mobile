package tech.imitravanu.eroflix.core.media
import org.junit.Assert.*
import org.junit.Test
class ExportPolicyTest {
    @Test fun stagingAndDestinationUse64BitOverflowSafeReservation() {
        val gib=1024L*1024*1024
        assertEquals(10*gib+gib/2,exportReservation(5*gib))
        assertEquals(2L*(100+1024*1024),exportReservation(100))
        assertNull(exportReservation(Long.MAX_VALUE));assertNull(exportReservation(0));assertNull(exportReservation(-1))
    }
    @Test fun onlyFiniteClearContinuousHlsAdmitsRemux() {
        val vod="#EXTM3U\n#EXT-X-MAP:URI=init.mp4\n#EXTINF:4,\nseg.m4s\n#EXT-X-ENDLIST"
        assertTrue(exportManifestAllowed(vod))
        assertTrue(exportManifestAllowed("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8"))
        assertFalse(exportManifestAllowed(vod.replace("#EXT-X-ENDLIST","")))
        assertFalse(exportManifestAllowed(vod+"\n#EXT-X-DISCONTINUITY"))
        assertFalse(exportManifestAllowed(vod+"\n#EXT-X-KEY:METHOD=AES-128,URI=key.bin"))
        assertFalse(exportManifestAllowed(vod+"\n#EXT-X-PART:DURATION=1,URI=part.m4s"))
        assertFalse(exportManifestAllowed("<html>failure</html>"))
    }
}
