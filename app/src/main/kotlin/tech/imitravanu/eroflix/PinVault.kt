package tech.imitravanu.eroflix

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN verifier only; lifecycle and biometric gates must be completed before exposing app lock. */
internal class PinVault(context: Context, private val prefsName: String = "privacy-lock-v1", private val keyAlias: String = "eroflix.pin.verifier.v1",
    private val elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val boot: () -> Int = { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) },
    private val commit: (android.content.SharedPreferences.Editor) -> Boolean = { it.commit() }) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    sealed interface Result {
        data object Success : Result
        data object InvalidPin : Result
        data object Unavailable : Result
        data class RetryAfter(val milliseconds: Long) : Result
    }
    val enrolled: Boolean get() = prefs.getBoolean("enrolled", false)

    // SharedPreferences changes its memory cache even when the disk write fails.
    // Restore the previous record in memory (and attempt to restore disk) on failure.
    private fun write(change: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        val previous = prefs.all.toMap()
        val ok = try { commit(prefs.edit().apply(change)) } catch (_: Exception) { false }
        if (!ok) {
            val restore = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            runCatching { restore.commit() }
        }
        return ok
    }

    private fun key(create: Boolean): SecretKey? {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias,null) as? SecretKey)?.let { return it }
        if(!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
    }
    private fun validPin(pin: CharArray) = pin.size in 4..64 && pin.all { it in '0'..'9' }
    private fun verifier(pin: CharArray,salt: ByteArray,key: SecretKey): ByteArray {
        val spec=PBEKeySpec(pin,salt,210_000,256)
        val derived=try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(derived) } finally { derived.fill(0) }
    }
    internal class PreparedPin(val salt: ByteArray,val hash: ByteArray) {
        fun clear() { salt.fill(0);hash.fill(0) }
    }
    // Preparation can be slow; the controller checks the biometric proof and
    // foreground epoch again before committing the replacement.
    @Synchronized fun prepareReplacement(pin: CharArray): PreparedPin? {
        if(!enrolled || !validPin(pin)) return null
        return try {
            val secret=key(true) ?: return null
            val salt=ByteArray(32).also(random::nextBytes)
            PreparedPin(salt,verifier(pin,salt,secret))
        } catch(_: Exception) { null }
    }
    @Synchronized fun commitReplacement(prepared: PreparedPin): Result {
        if(!enrolled) return Result.Unavailable
        return if(write {
            putString("salt",android.util.Base64.encodeToString(prepared.salt,android.util.Base64.NO_WRAP))
            putString("verifier",android.util.Base64.encodeToString(prepared.hash,android.util.Base64.NO_WRAP))
            putInt("failures",0).putLong("retryDelay",0).putLong("retryDeadline",0).remove("retryAt")
        }) Result.Success else Result.Unavailable
    }
    @Synchronized fun enroll(pin: CharArray): Result {
        if(enrolled || !validPin(pin)) return Result.InvalidPin
        return try {
            val secret=key(true) ?: return Result.Unavailable
            val salt=ByteArray(32).also(random::nextBytes)
            val hash=verifier(pin,salt,secret)
            val ok=write { putString("salt",android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP))
                .putString("verifier",android.util.Base64.encodeToString(hash,android.util.Base64.NO_WRAP))
                .putInt("failures",0).putLong("retryAt",0).putBoolean("enrolled",true) }
            hash.fill(0);salt.fill(0)
            if(ok) Result.Success else Result.Unavailable
        } catch(_: Exception) { Result.Unavailable }
    }
    @Synchronized fun verify(pin: CharArray): Result {
        if(!enrolled) return Result.Unavailable
        val nowMs=elapsed()
        val currentBoot=boot()
        // On reboot (or migration from the wall-clock format), restart at most the
        // previously imposed delay. Persist the new deadline before using it.
        val oldDelay=prefs.getLong("retryDelay", if(prefs.getLong("retryAt",0)>0) 60_000 else 0).coerceIn(0,60_000)
        if(oldDelay>0 && (prefs.getInt("retryBoot", Int.MIN_VALUE)!=currentBoot || !prefs.contains("retryDeadline"))) {
            if(!write { putInt("retryBoot",currentBoot).putLong("retryDeadline",nowMs+oldDelay).remove("retryAt") }) return Result.Unavailable
        }
        val remaining=(prefs.getLong("retryDeadline",0)-nowMs).coerceIn(0,60_000)
        if(remaining>0) return Result.RetryAfter(remaining)
        if(!validPin(pin)) return failed(nowMs)
        return try {
            val secret=key(false) ?: return Result.Unavailable
            val salt=android.util.Base64.decode(prefs.getString("salt",null) ?: return Result.Unavailable,android.util.Base64.NO_WRAP)
            val saved=android.util.Base64.decode(prefs.getString("verifier",null) ?: return Result.Unavailable,android.util.Base64.NO_WRAP)
            if(salt.size!=32 || saved.size!=32) return Result.Unavailable
            val actual=verifier(pin,salt,secret)
            val matches=MessageDigest.isEqual(saved,actual)
            actual.fill(0);saved.fill(0);salt.fill(0)
            if(matches) {
                if(write { putInt("failures",0).putLong("retryDelay",0).putLong("retryDeadline",0).remove("retryAt") }) Result.Success else Result.Unavailable
            } else failed(nowMs)
        } catch(_: Exception) { Result.Unavailable }
    }
    @Synchronized fun disableAfterVerification(): Result {
        if(!write { clear() }) return Result.Unavailable
        // An orphaned key is harmless; never erase enrollment if verification failed.
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(keyAlias) } }
        return Result.Success
    }
    private fun failed(nowMs: Long): Result {
        val attempts=(prefs.getInt("failures",0)+1).coerceAtMost(20)
        val delay=if(attempts<5) 0L else (1_000L shl (attempts-5).coerceAtMost(6)).coerceAtMost(60_000L)
        return if(write { putInt("failures",attempts).putLong("retryDelay",delay).putLong("retryDeadline",nowMs+delay).putInt("retryBoot",boot()).remove("retryAt") }) Result.InvalidPin else Result.Unavailable
    }
}
