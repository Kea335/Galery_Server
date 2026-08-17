package com.kadr.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class StateCount(val state: AssetState, val count: Int)

/**
 * States that still owe the server something. Written out as literals because
 * Room cannot bind a list of enums into an IN clause through a type converter.
 */
private const val PENDING_STATES = "'DISCOVERED', 'HASHED', 'CHECKED', 'UPLOADING', 'FAILED'"

@Dao
interface LocalAssetDao {

    @Query("SELECT * FROM local_assets ORDER BY COALESCE(capturedAt, dateModified) DESC")
    fun observeAll(): Flow<List<LocalAsset>>

    @Query("SELECT state, COUNT(*) AS count FROM local_assets GROUP BY state")
    fun observeStateCounts(): Flow<List<StateCount>>

    @Query("SELECT * FROM local_assets WHERE state = 'FAILED' ORDER BY id DESC LIMIT :limit")
    fun observeFailed(limit: Int = 50): Flow<List<LocalAsset>>

    @Query("SELECT * FROM local_assets WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun findByMediaStoreId(mediaStoreId: Long): LocalAsset?

    @Query("SELECT * FROM local_assets WHERE id = :id")
    suspend fun findById(id: Long): LocalAsset?

    /**
     * Oldest untouched work first, and rows that have already failed go last so
     * one poisoned file cannot block the queue behind it (§10.4).
     */
    @Query(
        """
        SELECT * FROM local_assets
        WHERE state IN ($PENDING_STATES) AND attemptCount < :maxAttempts
        ORDER BY attemptCount ASC, COALESCE(capturedAt, dateModified) DESC
        LIMIT 1
        """,
    )
    suspend fun nextPending(maxAttempts: Int = Int.MAX_VALUE): LocalAsset?

    @Query(
        """
        SELECT * FROM local_assets
        WHERE state IN ($PENDING_STATES) AND attemptCount < :maxAttempts
        ORDER BY attemptCount ASC, COALESCE(capturedAt, dateModified) DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingBatch(maxAttempts: Int, limit: Int): List<LocalAsset>

    @Query(
        "SELECT COUNT(*) FROM local_assets WHERE state IN ($PENDING_STATES) AND attemptCount < :maxAttempts",
    )
    suspend fun pendingCount(maxAttempts: Int): Int

    @Query("SELECT COUNT(*) FROM local_assets")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM local_assets WHERE state = :state")
    suspend fun countByState(state: AssetState): Int

    /**
     * Candidates for "free up space" (§10.7): verified, hashed, and the file is
     * still on the device. The server is asked to confirm these again before
     * anything is deleted.
     */
    @Query(
        "SELECT * FROM local_assets WHERE state = 'VERIFIED' AND sha256 IS NOT NULL ORDER BY sizeBytes DESC",
    )
    suspend fun verifiedWithLocalCopy(): List<LocalAsset>

    /** The same candidates, narrowed to what the user picked out of the grid. */
    @Query(
        """
        SELECT * FROM local_assets
        WHERE id IN (:ids) AND state = 'VERIFIED' AND sha256 IS NOT NULL
        ORDER BY sizeBytes DESC
        """,
    )
    suspend fun verifiedWithLocalCopy(ids: List<Long>): List<LocalAsset>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM local_assets WHERE state = 'VERIFIED'")
    suspend fun reclaimableBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM local_assets WHERE state = 'LOCAL_FREED'")
    suspend fun freedBytes(): Long

    /**
     * The server already holds these hashes, so they never need sending.
     * `remoteId` stays null: §9's check endpoint only reports what is missing,
     * and the gallery's delta sync fills the ids in later.
     */
    @Query("UPDATE local_assets SET state = 'VERIFIED', lastError = NULL WHERE id IN (:ids)")
    suspend fun markVerified(ids: List<Long>)

    @Query("UPDATE local_assets SET state = 'CHECKED', lastError = NULL WHERE id IN (:ids)")
    suspend fun markChecked(ids: List<Long>)

    /** User-driven "try these again", which clears the attempt cap. */
    @Query("UPDATE local_assets SET attemptCount = 0, lastError = NULL WHERE state = 'FAILED'")
    suspend fun resetFailures(): Int

    @Query("SELECT COUNT(*) FROM local_assets WHERE state = 'FAILED' AND attemptCount >= :maxAttempts")
    suspend fun exhaustedCount(maxAttempts: Int): Int

    @Insert
    suspend fun insert(asset: LocalAsset): Long

    @Update
    suspend fun update(asset: LocalAsset)

    @Query("DELETE FROM local_assets")
    suspend fun clear()
}
