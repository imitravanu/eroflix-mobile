package tech.imitravanu.eroflix

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class PrivacyUi(val enrolled: Boolean,val locked: Boolean,val busy: Boolean=false,val message: String?=null,val biometricEnabled: Boolean=false,val recoveryAuthorized: Boolean=false)

/** Process-local unlock state; persisted credentials alone never unlock a session. */
internal class PrivacyController(private val vault: PinVault,private val biometric: BiometricVault?=null,private val applyLock: (Boolean)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(PrivacyUi(vault.enrolled,vault.enrolled,biometricEnabled=vault.enrolled && biometric?.enabled == true))
    val state=mutable.asStateFlow()
    private var visible=false
    private var epoch=0L
    private enum class BiometricAction { ENROLL, UNLOCK, RECOVER }
    private var biometricAttempt: Triple<Long,BiometricAction,javax.crypto.Cipher>? = null
    fun sync() { applyLock(state.value.locked) }
    fun visible(value: Boolean,pip: Boolean) {
        visible=value || pip
        if(!visible) lock()
    }
    fun lock() {
        epoch++
        if(biometricAttempt!=null) mutable.update { it.copy(busy=false) }
        biometricAttempt=null
        if(state.value.enrolled) { mutable.update { it.copy(locked=true,message=null,recoveryAuthorized=false) };sync() }
    }
    fun enroll(pin: CharArray) = work(pin,setup=true,remove=false)
    fun unlock(pin: CharArray) = work(pin,setup=false,remove=false)
    fun disable(pin: CharArray) = work(pin,setup=false,remove=true)
    fun biometricUnavailable() { mutable.update { it.copy(message="Fingerprint is unavailable. Use your PIN.") } }
    fun beginBiometric(setup: Boolean,recover: Boolean=false): javax.crypto.Cipher? {
        val current=state.value
        if(!visible || current.busy || !current.enrolled || biometric==null || (setup && current.locked) || (!setup && (!current.locked || !current.biometricEnabled))) return null
        return try {
            val cipher=if(setup) biometric.enrollmentCipher() else biometric.unlockCipher()
            biometricAttempt=Triple(epoch,if(setup) BiometricAction.ENROLL else if(recover) BiometricAction.RECOVER else BiometricAction.UNLOCK,cipher)
            mutable.update { it.copy(busy=true,message=null) }
            cipher
        } catch(_: Exception) {
            mutable.update { it.copy(message="Fingerprint is unavailable. Use your PIN.") }
            null
        }
    }
    fun finishBiometric(cipher: javax.crypto.Cipher?) {
        val attempt=biometricAttempt
        if(attempt==null || cipher !== attempt.third) return
        biometricAttempt=null
        val biometricVault=biometric
        if(!visible || attempt.first!=epoch || biometricVault==null) { mutable.update { it.copy(busy=false) };return }
        val success=runCatching { if(attempt.second==BiometricAction.ENROLL) biometricVault.finishEnrollment(attempt.third) else biometricVault.finishUnlock(attempt.third) }.getOrDefault(false)
        if(success) {
            mutable.update { it.copy(busy=false,biometricEnabled=true,
                locked=if(attempt.second==BiometricAction.UNLOCK) false else it.locked,
                recoveryAuthorized=attempt.second==BiometricAction.RECOVER,
                message=if(attempt.second==BiometricAction.ENROLL) "Fingerprint unlock enabled." else null) }
            sync()
        } else mutable.update { it.copy(busy=false,message="Fingerprint is unavailable. Use your PIN.") }
    }
    fun cancelBiometric(cipher: javax.crypto.Cipher,message: String?=null) {
        if(biometricAttempt?.third !== cipher) return
        biometricAttempt=null
        mutable.update { it.copy(busy=false,message=message) }
    }
    fun recoverPin(pin: CharArray) {
        if(!visible || !state.value.locked || !state.value.recoveryAuthorized || state.value.busy) { pin.fill('\u0000');return }
        val started=epoch
        mutable.update { it.copy(busy=true,message=null) }
        scope.launch {
            val prepared=try { withContext(Dispatchers.IO) { vault.prepareReplacement(pin) } } finally { pin.fill('\u0000') }
            if(prepared==null) { mutable.update { it.copy(busy=false,message="Could not prepare the new PIN. Use 4 to 64 digits.") };return@launch }
            try {
                if(!visible || epoch!=started || !state.value.locked || !state.value.recoveryAuthorized) {
                    mutable.update { it.copy(busy=false,recoveryAuthorized=false,message="Authenticate again to recover the PIN.") };return@launch
                }
                val result=vault.commitReplacement(prepared)
                if(result==PinVault.Result.Success) {
                    mutable.update { it.copy(busy=false,locked=false,recoveryAuthorized=false,message="PIN changed.") };sync()
                } else mutable.update { it.copy(busy=false,recoveryAuthorized=false,message="Could not save the new PIN. Your previous PIN remains required.") }
            } finally { prepared.clear() }
        }
    }
    fun cancelRecovery() { mutable.update { it.copy(recoveryAuthorized=false,message=null) } }
    private fun work(pin: CharArray,setup: Boolean,remove: Boolean) {
        if(state.value.busy) { pin.fill('\u0000');return }
        val started=epoch
        mutable.update { it.copy(busy=true,message=null) }
        scope.launch {
            val result=try { withContext(Dispatchers.IO) {
                if(setup) vault.enroll(pin) else vault.verify(pin)
            } } finally { pin.fill('\u0000') }
            val stillVisible=visible && epoch==started
            when(result) {
                PinVault.Result.Success -> {
                    if(remove && !stillVisible) {
                        mutable.update { it.copy(busy=false,locked=true,message="Unlock before changing the lock.") };sync();return@launch
                    }
                    if(remove && biometric?.enabled==true && !biometric.clear()) {
                        mutable.update { it.copy(busy=false,message="Could not disable fingerprint. Try again.") };return@launch
                    }
                    if(remove && vault.disableAfterVerification()!=PinVault.Result.Success) {
                        mutable.update { it.copy(busy=false,biometricEnabled=biometric?.enabled==true,message="Could not disable the lock. Try again.") };return@launch
                    }
                    val enrolled=vault.enrolled
                    mutable.value=PrivacyUi(enrolled,if(enrolled) !stillVisible else false,
                        message=if(enrolled && !stillVisible) "Unlock to continue." else null,
                        biometricEnabled=enrolled && biometric?.enabled==true)
                    sync()
                }
                PinVault.Result.InvalidPin -> mutable.update { it.copy(busy=false,message=if(setup) "Use a PIN of 4 to 64 digits." else "Incorrect PIN.") }
                is PinVault.Result.RetryAfter -> mutable.update { it.copy(busy=false,message="Try again in ${(result.milliseconds+999)/1000} seconds.") }
                PinVault.Result.Unavailable -> {
                    // A failed persistence operation must never make an enrolled lock appear disabled.
                    val enrolled=vault.enrolled || state.value.enrolled
                    mutable.update { it.copy(enrolled=enrolled,locked=enrolled,busy=false,message="PIN verification is unavailable. Your data is unchanged.") }
                    sync()
                }
            }
        }
    }
}
