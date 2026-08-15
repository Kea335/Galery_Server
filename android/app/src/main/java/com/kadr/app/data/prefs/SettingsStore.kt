package com.kadr.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kadr.app.data.media.MediaStoreScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class KadrSettings(
    val serverUrl: String = "",
    val deviceId: String = "",
    val token: String = "",
    val excludedFolders: Set<String> = MediaStoreScanner.DEFAULT_EXCLUDED_FOLDERS,

    // §10.5 — network and battery rules
    val autoBackup: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    /** Skip videos larger than this. Zero means no limit. */
    val maxVideoMb: Int = 0,

    /** Delta-sync cursor for the server library (§9). */
    val librarySince: Long = 0,

    /** §11 — media cache, 512 MB by default. */
    val videoCacheMb: Int = 512,

    /** §12 — Material You is available, but never the default. */
    val dynamicColor: Boolean = false,
) {
    val isPaired: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/**
 * The device token is a bearer credential for the whole library, so it lives in
 * EncryptedSharedPreferences behind a Keystore-backed master key (§6, §13).
 *
 * Note: androidx.security.crypto is deprecated upstream. It still works, but
 * token storage needs a replacement before v1 — see android/README.md.
 */
@Singleton
class SettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "kadr_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<KadrSettings> = _settings.asStateFlow()

    val current: KadrSettings get() = _settings.value

    private fun read() = KadrSettings(
        serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
        deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        excludedFolders = prefs.getStringSet(KEY_EXCLUDED, null)
            ?: MediaStoreScanner.DEFAULT_EXCLUDED_FOLDERS,
        autoBackup = prefs.getBoolean(KEY_AUTO_BACKUP, true),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        chargingOnly = prefs.getBoolean(KEY_CHARGING_ONLY, false),
        includeVideos = prefs.getBoolean(KEY_INCLUDE_VIDEOS, true),
        maxVideoMb = prefs.getInt(KEY_MAX_VIDEO_MB, 0),
        librarySince = prefs.getLong(KEY_LIBRARY_SINCE, 0),
        videoCacheMb = prefs.getInt(KEY_VIDEO_CACHE_MB, 512),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
    )

    fun saveServerUrl(url: String) = edit { putString(KEY_SERVER_URL, url.trim()) }

    fun savePairing(serverUrl: String, deviceId: String, token: String) = edit {
        putString(KEY_SERVER_URL, serverUrl.trim())
        putString(KEY_DEVICE_ID, deviceId)
        putString(KEY_TOKEN, token)
    }

    fun saveExcludedFolders(folders: Set<String>) = edit { putStringSet(KEY_EXCLUDED, folders) }

    fun setAutoBackup(enabled: Boolean) = edit { putBoolean(KEY_AUTO_BACKUP, enabled) }

    fun setWifiOnly(enabled: Boolean) = edit { putBoolean(KEY_WIFI_ONLY, enabled) }

    fun setChargingOnly(enabled: Boolean) = edit { putBoolean(KEY_CHARGING_ONLY, enabled) }

    fun setIncludeVideos(enabled: Boolean) = edit { putBoolean(KEY_INCLUDE_VIDEOS, enabled) }

    fun setMaxVideoMb(limit: Int) = edit { putInt(KEY_MAX_VIDEO_MB, limit.coerceAtLeast(0)) }

    fun saveLibrarySince(value: Long) = edit { putLong(KEY_LIBRARY_SINCE, value) }

    fun setVideoCacheMb(value: Int) = edit { putInt(KEY_VIDEO_CACHE_MB, value.coerceIn(64, 8192)) }

    fun setDynamicColor(enabled: Boolean) = edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }

    /**
     * Forgets the token but keeps the server address, so re-pairing is one
     * field. The sync cursor goes too — a different server, or the same one
     * after a restore, must be re-read from the beginning.
     */
    fun clearPairing() = edit {
        remove(KEY_DEVICE_ID)
        remove(KEY_TOKEN)
        remove(KEY_LIBRARY_SINCE)
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_EXCLUDED = "excluded_folders"
        const val KEY_AUTO_BACKUP = "auto_backup"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_CHARGING_ONLY = "charging_only"
        const val KEY_INCLUDE_VIDEOS = "include_videos"
        const val KEY_MAX_VIDEO_MB = "max_video_mb"
        const val KEY_LIBRARY_SINCE = "library_since"
        const val KEY_VIDEO_CACHE_MB = "video_cache_mb"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
