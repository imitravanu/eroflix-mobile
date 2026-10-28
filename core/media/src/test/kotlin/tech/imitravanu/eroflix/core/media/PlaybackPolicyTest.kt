package tech.imitravanu.eroflix.core.media
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun resumeNeedsExactTimelineAndFiniteCompatibleDuration() {
        assertTrue(compatibleTimeline("same","same",100_000,100_500))
        assertFalse(compatibleTimeline("a","b",100_000,100_000))
        assertFalse(compatibleTimeline(null,"b",100_000,100_000))
        assertFalse(compatibleTimeline("same","same",null,100_000))
        assertFalse(compatibleTimeline("same","same",100_000,102_000))
    }
    @Test fun intervalsExcludeSeekRestoreStallAndLongBackgroundGap() {
        val intervals=AdvancingIntervals()
        assertNull(intervals.sample(0,0,true,1f,false))
        assertEquals(0L,intervals.sample(1000,1000,true,1f,false))
        assertNull(intervals.sample(2000,50000,true,1f,true))
        assertEquals(50000L,intervals.sample(3000,51000,true,1f,false))
        assertNull(intervals.sample(4000,51000,false,1f,false))
        assertNull(intervals.sample(15000,52000,true,1f,false))
    }
    @Test fun speedIsAccountedForWithoutCountingImpossibleJumps() {
        val intervals=AdvancingIntervals()
        intervals.sample(0,1000,true,2f,true)
        assertEquals(1000L,intervals.sample(1000,3000,true,2f,false))
        assertNull(intervals.sample(2000,9000,true,2f,false))
    }
    @Test fun gainIsBoundedSymmetricAndNeverClips() {
        assertEquals(.1f,gainedSample(.1f,1f),.00001f)
        assertTrue(gainedSample(.1f,2f)>.1f)
        for(i in -100..100) {
            val x=i/100f
            assertTrue(kotlin.math.abs(gainedSample(x,2f))<=1)
            assertEquals(-gainedSample(x,2f),gainedSample(-x,2f),.00001f)
        }
        assertEquals(gainedSample(.5f,2f),gainedSample(.5f,10f),.00001f)
    }
    @Test fun delayedResolutionCannotStartAfterHomeBackOrLock() {
        for(action in listOf<(PlaybackAdmission)->Unit>({ it.visible(false,false) },{ it.leave() },{ it.lock(true) })) {
            val policy=PlaybackAdmission();val token=policy.begin()!!
            assertTrue(policy.valid(token));action(policy)
            assertFalse(policy.valid(token))
            policy.visible(true,false)
            assertFalse(policy.valid(token))
        }
    }
    @Test fun stopNeverClearsAuthenticationLockAndRestorationNeverReservesRoute() {
        val policy=PlaybackAdmission()
        assertFalse(policy.route)
        policy.lock(true);policy.stop()
        assertTrue(policy.locked);assertNull(policy.begin())
        policy.lock(false)
        assertNotNull(policy.begin())
    }
    @Test fun backgroundAudioDoesNotAuthorizeLateResolverStart() {
        val policy=PlaybackAdmission();val token=policy.begin()!!
        policy.backgroundAudio=true;policy.visible(false,false)
        assertFalse(policy.valid(token))
    }
}
