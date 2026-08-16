package com.kadr.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §6 and §13: the device token is a bearer credential for the whole library, so
 * it must not be readable from the app's own files.
 *
 * These run on a device because the Android Keystore is the thing under test —
 * there is no meaningful JVM stand-in for it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * One real preferences file backs the installed app, so a test that writes
     * to it would otherwise leave the app signed out. Put back what was there.
     */
    private lateinit var saved: KadrSettings

    @Before
    fun setUp() {
        saved = SettingsStore(context).current
    }

    @After
    fun tearDown() {
        SettingsStore(context).restore(saved)
    }

    @Test
    fun a_the_token_survives_a_restart_but_is_not_stored_in_the_clear() {
        val token = "kadr-token-${System.nanoTime()}"
        SettingsStore(context).savePairing("http://192.168.1.10:8080/", "device-1", token)

        // A second instance reads from disk with nothing cached in memory — the
        // same position the app is in after being killed.
        val reopened = SettingsStore(context).current
        assertEquals("The token has to survive a cold start", token, reopened.token)
        assertEquals("http://192.168.1.10:8080/", reopened.serverUrl)
        assertEquals("device-1", reopened.deviceId)
        assertTrue(reopened.isPaired)

        val raw = context.getSharedPreferences("kadr_prefs", Context.MODE_PRIVATE)
            .getString("token", null)
        assertNotNull("Something should be on disk under the token key", raw)
        assertFalse("The token must never be readable at rest", raw == token)
        assertFalse(
            "…nor hidden anywhere else in the file",
            prefsFileText(context, "kadr_prefs").contains(token),
        )
    }

    @Test
    fun b_the_address_is_kept_but_the_token_is_gone_after_signing_out() {
        SettingsStore(context).savePairing("http://192.168.1.10:8080/", "device-2", "to-be-forgotten")

        SettingsStore(context).clearPairing()

        val after = SettingsStore(context).current
        assertEquals("", after.token)
        assertEquals("", after.deviceId)
        assertFalse(after.isPaired)
        assertEquals(
            "Re-signing in should be one field, so the address stays",
            "http://192.168.1.10:8080/",
            after.serverUrl,
        )
    }

    @Test
    fun c_settings_that_are_not_secrets_round_trip_unchanged() {
        val store = SettingsStore(context)
        val folders = setOf("Pictures/Kadr", "DCIM/Screenshots")

        store.setWifiOnly(false)
        store.setChargingOnly(true)
        store.setMaxVideoMb(250)
        store.saveLibrarySince(1_700_000_000_000)
        store.saveExcludedFolders(folders)

        val reopened = SettingsStore(context).current
        assertFalse(reopened.wifiOnly)
        assertTrue(reopened.chargingOnly)
        assertEquals(250, reopened.maxVideoMb)
        assertEquals(1_700_000_000_000, reopened.librarySince)
        assertEquals(folders, reopened.excludedFolders)
    }

    @Test
    fun d_an_unreadable_token_reads_as_signed_out_rather_than_crashing() {
        SettingsStore(context).savePairing("http://192.168.1.10:8080/", "device-3", "real-token")

        // What a wiped Keystore looks like from here: the ciphertext is still on
        // disk, but nothing can open it.
        context.getSharedPreferences("kadr_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("token", "bm90LWEtcmVhbC1jaXBoZXJ0ZXh0")
            .commit()

        val after = SettingsStore(context).current
        assertEquals("Garbage must read as no token at all", "", after.token)
        assertFalse("…which sends the user to the sign-in screen", after.isPaired)
    }
}
