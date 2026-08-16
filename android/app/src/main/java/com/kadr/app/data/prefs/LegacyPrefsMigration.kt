// Reading the old file is the whole job of this file, and the class that wrote
// it is deprecated. Suppressed here and nowhere else, so the warning still
// means something everywhere it appears.
@file:Suppress("DEPRECATION")

package com.kadr.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

private const val TAG = "KadrPrefsMigration"

/** The file androidx.security.crypto wrote, back when it held everything. */
internal const val LEGACY_PREFS_NAME = "kadr_secure_prefs"

/**
 * Moves an existing install off `androidx.security.crypto` exactly once.
 *
 * Only the token was ever a secret, so everything else crosses over unchanged
 * and the token is re-encrypted under our own Keystore key. The old file is
 * deleted the moment the new one is durably written, so a phone that is already
 * paired stays paired and nobody is asked to sign in again for a library they
 * already have.
 *
 * This is the only code left that touches the deprecated library, and it can be
 * deleted outright a release after v1 ships.
 */
internal fun migrateLegacyPrefs(
    context: Context,
    target: SharedPreferences,
    cipher: KeystoreCipher,
    tokenKey: String,
) {
    // Asking SharedPreferences for the file would create it, so look on disk.
    val legacyFile = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$LEGACY_PREFS_NAME.xml")
    if (!legacyFile.exists()) return

    val legacy = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // The master key is gone, or the file is damaged. Nothing in there is
        // recoverable and retrying next launch would fail the same way, so drop
        // it and let the user sign in again.
        Log.w(TAG, "legacy settings could not be opened; discarding them", e)
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        return
    }

    val editor = target.edit()
    for ((key, value) in legacy.all) {
        when {
            key == tokenKey -> (value as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { editor.putString(key, cipher.encrypt(it)) }

            value is String -> editor.putString(key, value)
            value is Boolean -> editor.putBoolean(key, value)
            value is Int -> editor.putInt(key, value)
            value is Long -> editor.putLong(key, value)
            value is Float -> editor.putFloat(key, value)

            value is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }

    // commit, not apply: the old file is deleted on the next line, so the new
    // one has to be on disk first.
    if (!editor.commit()) {
        Log.w(TAG, "could not write migrated settings; leaving the old file alone")
        return
    }

    context.deleteSharedPreferences(LEGACY_PREFS_NAME)
    Log.i(TAG, "settings moved off androidx.security.crypto")
}
