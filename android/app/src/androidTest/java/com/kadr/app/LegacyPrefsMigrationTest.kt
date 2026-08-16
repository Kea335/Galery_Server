// The point of this file is to write the old format, so it has to call the
// deprecated library that produced it.
@file:Suppress("DEPRECATION")

package com.kadr.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone that was already paired under `androidx.security.crypto` must come
 * through the move to our own Keystore wrapper still paired. Signing someone out
 * of a library they already have is the one outcome this change must not cause.
 */
@RunWith(AndroidJUnit4::class)
class LegacyPrefsMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var saved: KadrSettings

    @Before
    fun setUp() {
        saved = SettingsStore(context).current
        context.deleteSharedPreferences(PREFS)
        context.deleteSharedPreferences(LEGACY)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(LEGACY)
        context.deleteSharedPreferences(PREFS)
        SettingsStore(context).restore(saved)
    }

    @Test
    fun a_an_already_paired_phone_stays_paired() {
        legacyPrefs().edit()
            .putString("server_url", "http://192.168.1.50:8080/")
            .putString("device_id", "old-device")
            .putString("token", "token-from-before")
            .putBoolean("wifi_only", false)
            .putInt("max_video_mb", 120)
            .putStringSet("excluded_folders", setOf("Pictures/Old"))
            .commit()

        val migrated = SettingsStore(context).current

        assertEquals("token-from-before", migrated.token)
        assertEquals("http://192.168.1.50:8080/", migrated.serverUrl)
        assertEquals("old-device", migrated.deviceId)
        assertTrue("The whole point is not having to sign in again", migrated.isPaired)

        // The settings that were never secrets come across too, so nobody has to
        // set their battery rules a second time.
        assertFalse(migrated.wifiOnly)
        assertEquals(120, migrated.maxVideoMb)
        assertEquals(setOf("Pictures/Old"), migrated.excludedFolders)
    }

    @Test
    fun b_the_old_file_is_gone_afterwards_and_the_token_is_not_in_the_new_one() {
        legacyPrefs().edit()
            .putString("server_url", "http://192.168.1.50:8080/")
            .putString("token", "token-from-before")
            .commit()
        assertTrue("The test needs the old file to exist", prefsFile(context, LEGACY).exists())

        SettingsStore(context)

        assertFalse(
            "Leaving it behind would leave a second copy of the credential around",
            prefsFile(context, LEGACY).exists(),
        )
        assertFalse(
            "The token must be encrypted in its new home, not copied across",
            prefsFileText(context, PREFS).contains("token-from-before"),
        )
    }

    @Test
    fun c_a_fresh_install_has_nothing_to_migrate() {
        val fresh = SettingsStore(context).current

        assertEquals("", fresh.token)
        assertFalse(fresh.isPaired)
        assertFalse("No old file should be conjured up", prefsFile(context, LEGACY).exists())
    }

    private fun legacyPrefs() = EncryptedSharedPreferences.create(
        context,
        LEGACY,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val PREFS = "kadr_prefs"
        const val LEGACY = "kadr_secure_prefs"
    }
}
