package com.kadr.app

import android.content.Context
import com.kadr.app.data.prefs.KadrSettings
import com.kadr.app.data.prefs.SettingsStore
import java.io.File

/**
 * One real preferences file backs the installed app, and these tests write to
 * it. Anything a test changes has to be put back exactly, or the app on the
 * device is left signed out or with somebody's battery rules rewritten.
 */
fun SettingsStore.restore(saved: KadrSettings) {
    savePairing(saved.serverUrl, saved.deviceId, saved.token)
    saveExcludedFolders(saved.excludedFolders)
    saveLibrarySince(saved.librarySince)
    setAutoBackup(saved.autoBackup)
    setWifiOnly(saved.wifiOnly)
    setChargingOnly(saved.chargingOnly)
    setIncludeVideos(saved.includeVideos)
    setMaxVideoMb(saved.maxVideoMb)
    setVideoCacheMb(saved.videoCacheMb)
    setDynamicColor(saved.dynamicColor)
}

fun prefsFile(context: Context, name: String): File =
    File(File(context.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

/**
 * The bytes actually on disk. `SharedPreferences.apply()` writes on a background
 * thread, so a test that reads the file the instant after saving can beat it
 * there — wait for the file rather than assume.
 */
fun prefsFileText(context: Context, name: String): String {
    val file = prefsFile(context, name)
    val deadline = System.currentTimeMillis() + 5_000
    while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    return file.readText()
}
