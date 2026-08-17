package com.kadr.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The server's albums, mirrored locally (§16.6).
 *
 * Albums live on the server because §16 made the library shared: one that only
 * existed on this phone would contradict the library it belongs to. This table
 * is a cache of that, kept current by delta sync.
 *
 * Tombstones are kept for the same reason the asset mirror keeps them — a row
 * that is simply gone is not something a later sync can tell anyone about.
 */
@Entity(tableName = "remote_albums")
data class RemoteAlbum(
    @PrimaryKey val id: String,
    val name: String,
    val coverAssetId: String?,
    val createdAt: Long,
    val deleted: Boolean,
    val updatedAt: Long,
)

/**
 * Which photos are in which album.
 *
 * `removed` rather than a deleted row: the server tombstones membership so the
 * removal can travel, and throwing that away here would put the photo back in
 * the album on the next sync.
 */
@Entity(
    tableName = "album_items",
    primaryKeys = ["albumId", "assetId"],
    indices = [Index(value = ["albumId"]), Index(value = ["assetId"])],
)
data class AlbumItem(
    val albumId: String,
    val assetId: String,
    val addedAt: Long,
    val removed: Boolean,
    val updatedAt: Long,
)

/** One row of the album list: the album, how full it is, and what to show. */
data class AlbumSummary(
    val id: String,
    val name: String,
    val itemCount: Int,
    /** Local file for the cover when this phone still has it — instant to draw. */
    val coverLocalUri: String?,
    /** Server id for the cover, for when it does not. */
    val coverRemoteId: String?,
)
