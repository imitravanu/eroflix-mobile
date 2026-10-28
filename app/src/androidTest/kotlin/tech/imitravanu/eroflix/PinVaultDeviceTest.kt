package tech.imitravanu.eroflix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PinVaultDeviceTest {
    @Test fun fingerprintCannotBeEnabledWithoutAuthenticationOrAfterLock() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-pin-${UUID.randomUUID()}";val alias="eroflix.test.${UUID.randomUUID()}"
        val biometricName="test-biometric-${UUID.randomUUID()}";val biometricAlias="eroflix.test.biometric.${UUID.randomUUID()}"
        try {
            val biometric=BiometricVault(context,biometricName,biometricAlias)
            val controller=withContext(Dispatchers.Main) { PrivacyController(PinVault(context,name,alias),biometric) {} }
            withContext(Dispatchers.Main) { controller.visible(true,false);controller.enroll("1234".toCharArray()) }
            assertTrue(controller.state.first { !it.busy }.enrolled)
            withContext(Dispatchers.Main) {
                controller.recoverPin("9999".toCharArray())
                assertFalse(controller.state.value.recoveryAuthorized)
                val cipher=requireNotNull(controller.beginBiometric(true))
                controller.finishBiometric(cipher)
                assertFalse(controller.state.value.biometricEnabled)
                assertFalse(biometric.enabled)
                val delayed=requireNotNull(controller.beginBiometric(true))
                controller.visible(false,false)
                controller.finishBiometric(delayed)
                assertTrue(controller.state.value.locked)
                assertFalse(controller.state.value.biometricEnabled)
                assertFalse(controller.state.value.busy)
                controller.visible(true,false)
                controller.unlock("1234".toCharArray())
            }
            assertFalse(controller.state.first { !it.busy }.locked)
        } finally {
            context.deleteSharedPreferences(name);context.deleteSharedPreferences(biometricName)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias);deleteEntry(biometricAlias) }
        }
    }
    @Test fun pinSessionLocksOnBackgroundAndRejectsLateUnlock() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-pin-${UUID.randomUUID()}";val alias="eroflix.test.${UUID.randomUUID()}"
        try {
            var applied=false
            val controller=withContext(Dispatchers.Main) { PrivacyController(PinVault(context,name,alias)) { applied=it } }
            withContext(Dispatchers.Main) { controller.visible(true,false);controller.enroll("1234".toCharArray()) }
            assertTrue(controller.state.first { !it.busy }.enrolled)
            assertFalse(controller.state.value.locked)
            withContext(Dispatchers.Main) { controller.visible(false,false) }
            assertTrue(controller.state.value.locked);assertTrue(applied)
            withContext(Dispatchers.Main) { controller.visible(true,false);controller.unlock("1234".toCharArray());controller.visible(false,false) }
            assertTrue(controller.state.first { !it.busy }.locked)
            withContext(Dispatchers.Main) { controller.visible(true,false);controller.unlock("1234".toCharArray()) }
            assertFalse(controller.state.first { !it.busy }.locked)
            withContext(Dispatchers.Main) { assertFalse(applied) }
            withContext(Dispatchers.Main) { controller.disable("0000".toCharArray()) }
            assertTrue(controller.state.first { !it.busy }.enrolled)
            withContext(Dispatchers.Main) { controller.disable("1234".toCharArray()) }
            assertFalse(controller.state.first { !it.busy }.enrolled)
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        }
    }
    @Test fun verifierPersistsLimitsRetriesAndFailsClosedWhenKeyIsMissing() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-pin-${UUID.randomUUID()}";val alias="eroflix.test.${UUID.randomUUID()}"
        try {
            val pin="1234".toCharArray()
            var now=1000L
            var boot=1
            var vault=PinVault(context,name,alias,elapsed={ now },boot={ boot })
            assertEquals(PinVault.Result.InvalidPin,vault.enroll("12".toCharArray()))
            assertEquals(PinVault.Result.Success,vault.enroll(pin))
            vault=PinVault(context,name,alias,elapsed={ now },boot={ boot })
            repeat(5) { assertEquals(PinVault.Result.InvalidPin,vault.verify("0000".toCharArray())) }
            assertTrue(vault.verify(pin) is PinVault.Result.RetryAfter)
            // A new boot restarts at most the prior delay, independently of wall time.
            boot=2;now=10
            assertEquals(PinVault.Result.RetryAfter(1000),vault.verify(pin))
            now=1011
            assertEquals(PinVault.Result.Success,vault.verify(pin))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
            assertEquals(PinVault.Result.Unavailable,vault.verify(pin))
            pin.fill('\u0000')
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        }
    }
    @Test fun failedDisableRestoresCredentialInMemory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-pin-${UUID.randomUUID()}";val alias="eroflix.test.${UUID.randomUUID()}"
        try {
            var fail=false
            val vault=PinVault(context,name,alias,commit={ editor ->
                val saved=editor.commit()
                if(fail) { fail=false; false } else saved
            })
            val pin="1234".toCharArray()
            assertEquals(PinVault.Result.Success,vault.enroll(pin))
            fail=true
            assertEquals(PinVault.Result.Unavailable,vault.disableAfterVerification())
            assertTrue(vault.enrolled)
            assertEquals(PinVault.Result.Success,vault.verify(pin))
            assertEquals(PinVault.Result.Success,PinVault(context,name,alias).verify(pin))
            assertEquals(PinVault.Result.Success,vault.disableAfterVerification())
            pin.fill('\u0000')
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        }
    }
    @Test fun preparedReplacementChangesVerifierOnlyAfterCommit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-pin-${UUID.randomUUID()}";val alias="eroflix.test.${UUID.randomUUID()}"
        try {
            val vault=PinVault(context,name,alias)
            val original="1234".toCharArray();val replacement="9876".toCharArray()
            assertEquals(PinVault.Result.Success,vault.enroll(original))
            val prepared=requireNotNull(vault.prepareReplacement(replacement))
            try {
                assertEquals(PinVault.Result.Success,vault.verify(original))
                assertEquals(PinVault.Result.Success,vault.commitReplacement(prepared))
                assertEquals(PinVault.Result.InvalidPin,vault.verify(original))
                assertEquals(PinVault.Result.Success,vault.verify(replacement))
            } finally { prepared.clear();original.fill('\u0000');replacement.fill('\u0000') }
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        }
    }
}
