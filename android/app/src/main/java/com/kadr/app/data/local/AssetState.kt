package com.kadr.app.data.local

/**
 * The backup state machine (§8). Room is the source of truth: every worker run
 * resumes from this column, so it has to survive reboot, force-stop and a
 * server that has been offline for a week.
 */
enum class AssetState {
    /** Seen in MediaStore, nothing computed yet. */
    DISCOVERED,

    /** SHA-256 computed and cached. */
    HASHED,

    /** Server confirmed it does NOT have this hash. */
    CHECKED,

    UPLOADING,

    /** Server holds it and the hash matched. Only this state may be freed. */
    VERIFIED,

    /** Original deleted from the device by an explicit user action. */
    LOCAL_FREED,

    /** Excluded folder or unsupported type. */
    SKIPPED,

    FAILED,
}
