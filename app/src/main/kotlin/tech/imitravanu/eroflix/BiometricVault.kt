package tech.imitravanu.eroflix

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A separate, biometric-enrollment-bound key; losing it does not invalidate the PIN. */
internal class BiometricVault(context: Context,private val prefsName: String="privacy-biometric-v1",private val alias: String="eroflix.biometric.v1") {
    private val prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
    private val sentinel="Eroflix biometric verification v1".toByteArray(Charsets.UTF_8)
    val enabled get()=prefs.getBoolean("enabled",false)
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun enrollmentCipher(): Cipher {
        check(!enabled)
        val keys=store();if(keys.containsAlias(alias)) keys.deleteEntry(alias)
        val key=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true).build())
        }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
    }
    fun finishEnrollment(cipher: Cipher): Boolean {
        val ciphertext=cipher.doFinal(sentinel)
        return prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("ciphertext",Base64.encodeToString(ciphertext,Base64.NO_WRAP)).putBoolean("enabled",true).commit()
    }
    fun unlockCipher(): Cipher {
        check(enabled)
        val key=store().getKey(alias,null) as? SecretKey ?: error("Biometric key unavailable")
        val iv=Base64.decode(prefs.getString("iv",null) ?: error("Biometric IV missing"),Base64.NO_WRAP)
        check(iv.size==12)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv)) }
    }
    fun finishUnlock(cipher: Cipher): Boolean {
        val ciphertext=Base64.decode(prefs.getString("ciphertext",null) ?: error("Biometric verifier missing"),Base64.NO_WRAP)
        return MessageDigest.isEqual(sentinel,cipher.doFinal(ciphertext))
    }
    fun clear(): Boolean {
        val previous=prefs.all.toMap()
        if(!prefs.edit().clear().commit()) {
            val restore=prefs.edit().clear()
            previous.forEach { (key,value) -> when(value) {
                is String -> restore.putString(key,value)
                is Boolean -> restore.putBoolean(key,value)
            } }
            runCatching { restore.commit() }
            return false
        }
        runCatching { store().deleteEntry(alias) }
        return true
    }
}
