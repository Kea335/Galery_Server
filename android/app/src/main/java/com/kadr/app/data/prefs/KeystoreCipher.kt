package com.kadr.app.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with the key kept inside the Android Keystore, where the app can
 * use it but never read it out.
 *
 * §6 asks for the device token to be stored encrypted; it does not ask for
 * `androidx.security.crypto`, which is deprecated upstream. This is the
 * replacement that README called for — small enough to read in one sitting,
 * which is the point for the one credential that unlocks the whole library.
 */
internal class KeystoreCipher(private val alias: String) {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // GCM insists on a fresh IV per message and the platform picks one for
        // us. It is not a secret, but it has to travel with the ciphertext, so
        // the stored value is: length byte, IV, ciphertext.
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + body.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, destinationOffset = 1)
        body.copyInto(packed, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * Null when the value cannot be read back — a wiped Keystore, a cleared app,
     * or bytes written under a key that no longer exists. The caller reads that
     * as "not signed in" and asks for the password again, which the user can
     * actually recover from. Throwing would be a crash on every cold start.
     */
    fun decrypt(stored: String): String? =
        try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val ivSize = packed[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, packed, 1, ivSize),
            )
            String(
                cipher.doFinal(packed, 1 + ivSize, packed.size - 1 - ivSize),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // Every way this fails means the same thing to the caller, and the
            // list is long: a bad tag, a missing key, a truncated Base64 body.
            Log.w(TAG, "stored secret is unreadable; treating it as absent", e)
            null
        }

    /** Drops the key, which makes every value ever written with it unreadable. */
    fun forget() {
        cached = null
        runCatching { keystore().deleteEntry(alias) }
            .onFailure { Log.w(TAG, "could not delete $alias", it) }
    }

    @Volatile
    private var cached: SecretKey? = null

    /**
     * Synchronised because two threads generating at once would leave the
     * loser's ciphertext unreadable — the second `generateKey` replaces the
     * alias rather than failing.
     */
    @Synchronized
    private fun key(): SecretKey {
        cached?.let { return it }

        val existing = (keystore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: generate()
        cached = key
        return key
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not lock-screen bound: §10 runs the batch at
                // night, on a locked phone, with nobody there to authenticate.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keystore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val TAG = "KadrCipher"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
