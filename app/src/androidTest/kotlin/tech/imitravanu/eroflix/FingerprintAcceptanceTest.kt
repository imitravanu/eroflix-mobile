package tech.imitravanu.eroflix

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Requires the phone owner to approve three actual fingerprint prompts. */
@RunWith(AndroidJUnit4::class)
class FingerprintAcceptanceTest {
    @Test fun enrollUnlockAndRecoverUsingActualFingerprint() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pinPrefs="test-pin-${UUID.randomUUID()}";val pinAlias="eroflix.test.${UUID.randomUUID()}"
        val bioPrefs="test-biometric-${UUID.randomUUID()}";val bioAlias="eroflix.test.biometric.${UUID.randomUUID()}"
        val vault=PinVault(context,pinPrefs,pinAlias)
        var scenario: ActivityScenario<FingerprintHarnessActivity>?=null
        try {
            val controller=withContext(Dispatchers.Main) { PrivacyController(vault,BiometricVault(context,bioPrefs,bioAlias)) {} }
            scenario=ActivityScenario.launch(Intent(context,FingerprintHarnessActivity::class.java))
            withContext(Dispatchers.Main) { controller.visible(true,false);controller.enroll("1234".toCharArray()) }
            assertTrue(withTimeout(90_000) { controller.state.first { !it.busy } }.enrolled)
            scenario.onActivity { showFingerprintPrompt(it,controller,true) }
            val enrolled=withTimeout(90_000) { controller.state.first { !it.busy } }
            assertTrue("Fingerprint enrollment failed: ${enrolled.message}",enrolled.biometricEnabled)
            withContext(Dispatchers.Main) { controller.lock() }
            scenario.onActivity { showFingerprintPrompt(it,controller,false) }
            val unlocked=withTimeout(90_000) { controller.state.first { !it.busy } }
            assertFalse("Fingerprint unlock failed: ${unlocked.message}",unlocked.locked)
            withContext(Dispatchers.Main) { controller.lock() }
            scenario.onActivity { showFingerprintPrompt(it,controller,false,true) }
            val authorized=withTimeout(90_000) { controller.state.first { !it.busy } }
            assertTrue("Fingerprint recovery failed: ${authorized.message}",authorized.recoveryAuthorized)
            assertTrue(authorized.locked)
            withContext(Dispatchers.Main) { controller.recoverPin("9876".toCharArray()) }
            assertFalse(withTimeout(90_000) { controller.state.first { !it.busy } }.locked)
            assertEquals(PinVault.Result.InvalidPin,vault.verify("1234".toCharArray()))
            assertEquals(PinVault.Result.Success,vault.verify("9876".toCharArray()))
        } finally {
            scenario?.close()
            context.deleteSharedPreferences(pinPrefs);context.deleteSharedPreferences(bioPrefs)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(pinAlias);deleteEntry(bioAlias) }
        }
    }
}
