package tech.imitravanu.eroflix

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

internal fun showFingerprintPrompt(activity: FragmentActivity, controller: PrivacyController, setup: Boolean, recover: Boolean=false) {
    if(BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
        controller.biometricUnavailable();return
    }
    val cipher=controller.beginBiometric(setup,recover) ?: return
    val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object: BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val authorized=result.cryptoObject?.cipher
            if(authorized == null) controller.cancelBiometric(cipher,"Fingerprint verification was unavailable. Use your PIN.")
            else controller.finishBiometric(authorized)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            controller.cancelBiometric(cipher,"Fingerprint canceled or unavailable. Use your PIN.")
        }
    })
    val info=BiometricPrompt.PromptInfo.Builder()
        .setTitle(if(setup) "Enable fingerprint unlock" else if(recover) "Recover your PIN" else "Unlock Eroflix")
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
    try { prompt.authenticate(info,BiometricPrompt.CryptoObject(cipher)) }
    catch(_: Exception) { controller.cancelBiometric(cipher,"Fingerprint is unavailable. Use your PIN.") }
}
