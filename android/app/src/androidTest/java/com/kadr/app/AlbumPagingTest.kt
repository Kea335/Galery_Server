package com.kadr.app

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.local.AlbumDao
import com.kadr.app.data.local.AlbumItem
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.local.KadrDatabase
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.RemoteAlbum
import com.kadr.app.data.local.RemoteAsset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An album is the timeline with a join in front of it (§16.6), which means it
 * inherits the timeline's one hazard: a page is a `LIMIT/OFFSET` window, so two
 * rows the database may return in either order can appear twice or not at all.
 *
 * A burst of shots shares a capture time, and a burst is exactly the sort of
 * thing someone puts in an album, so the ties here are deliberate.
 */
@RunWith(AndroidJUnit4::class)
class AlbumPagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: KadrDatabase
    private lateinit var dao: AlbumDao

    private val albumId = "album-1"
    private val pageSize = 4

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java).build()
        dao = database.albums()
        dao.upsertAlbums(
            listOf(
                RemoteAlbum(
                    id = albumId,
                    name = "Georgia",
                    coverAssetId = null,
                    createdAt = 1_000,
                    deleted = false,
                    updatedAt = 1_000,
                ),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun a_an_album_full_of_one_burst_shows_every_photo_exactly_once() = runBlocking {
        // 20 photos across 4 bursts of 5: every row in a burst shares a moment.
        seedLocalOnServer(count = 20, perBurst = 5)
        addAll((1..20).map { "srv-$it" })

        val paged = readEveryPage()

        assertEquals(20, paged.size)
        assertEquals("A photo landed on two pages or on none", 20, paged.map { it.key }.toSet().size)
    }

    @Test
    fun b_the_album_reads_newest_first_under_a_total_order() = runBlocking {
        seedLocalOnServer(count = 20, perBurst = 5)
        addAll((1..20).map { "srv-$it" })

        val paged = readEveryPage()
        val expected = paged.sortedWith(
            compareByDescending<GalleryItem> { it.capturedAt }.thenByDescending { it.key },
        )

        assertEquals("Album pages must arrive in the timeline's own order", expected, paged)
    }

    @Test
    fun c_a_photo_taken_out_of_the_album_stops_appearing() = runBlocking {
        seedLocalOnServer(count = 3, perBurst = 1)
        addAll(listOf("srv-1", "srv-2", "srv-3"))

        // What the server sends when someone removes it: the row stays, marked.
        dao.upsertItems(
            listOf(AlbumItem(albumId, "srv-2", addedAt = 1, removed = true, updatedAt = 9)),
        )

        val keys = readEveryPage().map { it.key }
        assertEquals(2, keys.size)
        assertFalse("A removed photo must leave the album", "l2" in keys)
        assertTrue("The others stay", "l1" in keys && "l3" in keys)
    }

    @Test
    fun d_a_photo_whose_local_copy_was_freed_is_still_in_the_album() = runBlocking {
        // Only the server has this one — the "freed up space" case (§10.7).
        database.gallery().upsertRemote(
            listOf(
                RemoteAsset(
                    id = "srv-only",
                    sha256 = "sha-elsewhere",
                    sizeBytes = 10,
                    mimeType = "image/jpeg",
                    filename = "freed.jpg",
                    capturedAt = 500_000,
                    uploadedAt = 500_000,
                    width = 10,
                    height = 10,
                    durationMs = null,
                    orientation = 0,
                    deleted = false,
                    updatedAt = 500_000,
                ),
            ),
        )
        addAll(listOf("srv-only"))

        val paged = readEveryPage()

        assertEquals(1, paged.size)
        assertEquals("rsrv-only", paged.first().key)
        assertTrue("It should read as a server-only photo", paged.first().isServerOnly)
    }

    @Test
    fun e_the_album_list_counts_what_is_in_it_and_picks_a_cover() = runBlocking {
        seedLocalOnServer(count = 3, perBurst = 1)
        addAll(listOf("srv-1", "srv-2", "srv-3"))
        dao.upsertItems(
            listOf(AlbumItem(albumId, "srv-3", addedAt = 1, removed = true, updatedAt = 9)),
        )

        val summary = dao.observeAlbums().first().single()

        assertEquals("Georgia", summary.name)
        assertEquals("A removed photo must not be counted", 2, summary.itemCount)
        // Newest of what is left. Seeding walks capturedAt downwards from srv-1.
        assertEquals("srv-1", summary.coverRemoteId)
    }

    @Test
    fun f_a_deleted_album_drops_off_the_list() = runBlocking {
        dao.upsertAlbums(
            listOf(
                RemoteAlbum(albumId, "Georgia", null, 1_000, deleted = true, updatedAt = 2_000),
            ),
        )

        assertTrue(dao.observeAlbums().first().isEmpty())
    }

    private suspend fun readEveryPage(): List<GalleryItem> {
        val source = dao.pagingAlbum(albumId)
        val all = mutableListOf<GalleryItem>()

        var params: PagingSource.LoadParams<Int> = PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = pageSize,
            placeholdersEnabled = false,
        )
        while (true) {
            val page = source.load(params) as PagingSource.LoadResult.Page
            all += page.data
            val next = page.nextKey ?: break
            params = PagingSource.LoadParams.Append(next, pageSize, false)
        }
        return all
    }

    private suspend fun addAll(assetIds: List<String>) {
        dao.upsertItems(
            assetIds.mapIndexed { index, id ->
                AlbumItem(albumId, id, addedAt = index.toLong(), removed = false, updatedAt = index.toLong())
            },
        )
    }

    /** Local rows that the server also holds — the ordinary backed-up case. */
    private suspend fun seedLocalOnServer(count: Int, perBurst: Int) {
        val base = 1_000_000L
        for (i in 1..count) {
            database.assets().insert(
                LocalAsset(
                    id = i.toLong(),
                    mediaStoreId = i.toLong(),
                    contentUri = "content://media/external/images/media/$i",
                    relativePath = "Pictures/",
                    filename = "photo-$i.jpg",
                    sizeBytes = 1024,
                    dateModified = base,
                    capturedAt = base - (i - 1) / perBurst * 1_000L,
                    mimeType = "image/jpeg",
                    durationMs = null,
                    width = 100,
                    height = 100,
                    orientation = 0,
                    sha256 = "sha-$i",
                    state = AssetState.VERIFIED,
                    remoteId = "srv-$i",
                ),
            )
        }
    }
}
