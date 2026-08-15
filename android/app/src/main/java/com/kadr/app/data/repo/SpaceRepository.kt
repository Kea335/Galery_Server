package com.kadr.app.data.repo

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.LocalAssetDao
import com.kadr.app.data.remote.ApiProvider
import com.kadr.app.data.remote.CheckRequest
import com.kadr.app.data.remote.apiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class FreeUpPlan(
    val assets: List<LocalAsset>,
    val totalBytes: Long,
    /** Rows that looked ready but the server could not vouch for. */
    val withheld: Int,
) {
    val isEmpty: Boolean get() = assets.isEmpty()
}

/**
 * "Free up space" (§10.7).
 *
 * Three rules, and none of them bend:
 *
 * 1. Never automatic. The user asks, every time.
 * 2. Only rows in VERIFIED state — and even then, the server is asked again,
 *    right before deleting, whether it still holds those exact hashes. A row
 *    marked VERIFIED weeks ago is a memory, not a guarantee.
 * 3. The deletion itself goes through the system dialog on API 30+, so the
 *    confirmation the user sees is Android's own.
 */
@Singleton
class SpaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: LocalAssetDao,
    private val apiProvider: ApiProvider,
    private val json: Json,
) {

    /**
     * What could be freed right now, after re-confirming with the server.
     * Anything the server cannot vouch for is counted in [FreeUpPlan.withheld]
     * and left alone.
     */
    suspend fun plan(): Result<FreeUpPlan> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = dao.verifiedWithLocalCopy()
            if (candidates.isEmpty()) return@runCatching FreeUpPlan(emptyList(), 0, 0)

            val api = apiProvider.api()
            val hashes = candidates.mapNotNull { it.sha256 }.distinct()

            // Ask in batches of 500 (§9) and collect everything still missing —
            // a hash the server reports as missing must not be deleted here.
            val missing = hashes.chunked(500).flatMap { chunk ->
                apiCall(json) { api.check(CheckRequest(chunk)) }.data.missing
            }.toSet()

            val safe = candidates.filter { it.sha256 != null && it.sha256 !in missing }
            val withheld = candidates.size - safe.size
            if (withheld > 0) {
                Log.w(TAG, "$withheld assets are marked verified but the server does not have them")
            }

            FreeUpPlan(
                assets = safe,
                totalBytes = safe.sumOf { it.sizeBytes },
                withheld = withheld,
            )
        }
    }

    /**
     * The system delete dialog for a plan. On API 30+ Android shows it and the
     * user confirms there; below that the app has to do it itself.
     */
    fun deleteRequest(plan: FreeUpPlan): PendingIntent? {
        if (plan.isEmpty || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = plan.assets.map { Uri.parse(it.contentUri) }
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    /**
     * Fallback for API 26–29, where there is no system dialog. The app's own
     * confirmation is the only gate, which is why the caller must have shown one.
     */
    suspend fun deleteDirectly(plan: FreeUpPlan): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (asset in plan.assets) {
            val deleted = runCatching {
                context.contentResolver.delete(Uri.parse(asset.contentUri), null, null)
            }.getOrDefault(0)
            if (deleted > 0) removed++
        }
        removed
    }

    /**
     * Records what actually left the device. Only rows whose file is genuinely
     * gone are marked freed — if the user unticked something in the system
     * dialog, its row stays VERIFIED.
     */
    suspend fun markFreed(plan: FreeUpPlan): Int = withContext(Dispatchers.IO) {
        var freed = 0
        for (asset in plan.assets) {
            val stillThere = runCatching {
                context.contentResolver.openInputStream(Uri.parse(asset.contentUri))?.use { true }
                    ?: false
            }.getOrDefault(false)

            if (!stillThere) {
                dao.update(asset.copy(state = AssetState.LOCAL_FREED, lastError = null))
                freed++
            }
        }
        freed
    }

    private companion object {
        const val TAG = "KadrSpace"
    }
}
