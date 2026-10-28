package tech.imitravanu.eroflix

import org.junit.Assert.*
import org.junit.Test

class PrivacySessionTest {
    @Test fun enrollmentStartsLockedAndBackgroundLocksWithoutClosingPip() {
        val session=PrivacySession(true)
        assertFalse(session.mayRevealProtectedContent())
        session.visibilityChanged(true,false);session.authenticated()
        session.visibilityChanged(false,true);assertTrue(session.mayRevealProtectedContent())
        session.visibilityChanged(false,false);assertFalse(session.mayRevealProtectedContent())
        session.visibilityChanged(true,false);assertFalse(session.mayRevealProtectedContent())
        session.authenticated();session.screenOff();assertFalse(session.mayRevealProtectedContent())
    }
    @Test fun promptDoesNotLockDuringTemporaryVisibilityLossButCannotUnlockOnCancel() {
        val session=PrivacySession(true)
        session.visibilityChanged(true,false);session.authenticationStarted()
        session.visibilityChanged(false,false);session.authenticationEnded()
        assertFalse(session.mayRevealProtectedContent())
        session.visibilityChanged(true,false);session.authenticationStarted();session.authenticated();session.authenticationEnded()
        assertTrue(session.mayRevealProtectedContent())
    }
    @Test fun disabledLockDoesNotBlockAndNewSessionNeverRestoresUnlock() {
        val disabled=PrivacySession(false);disabled.screenOff();assertTrue(disabled.mayRevealProtectedContent())
        val enabled=PrivacySession(true);enabled.authenticated();assertTrue(enabled.mayRevealProtectedContent())
        assertFalse(PrivacySession(true).mayRevealProtectedContent())
    }
}
