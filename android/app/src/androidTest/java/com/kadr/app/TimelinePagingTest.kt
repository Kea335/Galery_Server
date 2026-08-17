package com.kadr.app

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.GalleryDao
import com.kadr.app.data.local.GalleryItem
import com.kadr.app.data.local.KadrDatabase
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.local.RemoteAsset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §15 reads the timeline a page at a time so a 10,000-photo library costs no
 * more to open than a small one. Reading it in windows brings one risk the whole
 * list never had: if two rows may come back in either order, a `LIMIT/OFFSET`
 * window can show one of them twice and the other not at all.
 *
 * Photos taken in the same second are not rare — a burst makes a dozen — so
 * these tests deliberately stack ties on top of the page boundaries.
 */
@RunWith(AndroidJUnit4::class)
class TimelinePagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: KadrDatabase
    private lateinit var dao: GalleryDao

    /** Small enough that a boundary lands inside every group of ties. */
    private val pageSize = 4

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, KadrDatabase::class.java).build()
        dao = database.gallery()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun a_paging_through_ties_shows_every_photo_exactly_once() = runBlocking {
        // 30 photos in 6 bursts of 5. Every row in a burst shares a timestamp,
        // so date alone cannot decide their order.
        seedLocal(count = 30, perBurst = 5)

        val paged = readEveryPage()

        assertEquals("Every seeded photo should come back", 30, paged.size)
        assertEquals(
            "A photo appeared on two pages or on none",
            30,
            paged.map { it.key }.toSet().size,
        )
    }

    @Test
    fun b_pages_come_back_newest_first_with_a_total_order() = runBlocking {
        seedLocal(count = 30, perBurst = 5)

        val paged = readEveryPage()

        val expected = paged.sortedWith(
            compareByDescending<GalleryItem> { it.capturedAt }.thenByDescending { it.key },
        )
        assertEquals("Pages must arrive in the timeline's own order", expected, paged)
    }

    @Test
    fun c_a_photo_the_server_already_has_appears_once_not_twice() = runBlocking {
        seedLocal(count = 3, perBurst = 1)
        // One server row mirrors local asset 1; another is server-only.
        dao.upsertRemote(
            listOf(
                remote(id = "srv-dupe", sha256 = "sha-1", capturedAt = 1_000_000L),
                remote(id = "srv-only", sha256 = "sha-elsewhere", capturedAt = 900_000L),
            ),
        )

        val paged = readEveryPage()
        val keys = paged.map { it.key }

        assertEquals("Three local plus the server-only one", 4, paged.size)
        assertFalse("The duplicate must not surface again", "rsrv-dupe" in keys)
        assertTrue("A photo only the server has must be there", "rsrv-only" in keys)
    }

    @Test
    fun d_the_viewers_position_lookup_agrees_with_the_pages() = runBlocking {
        seedLocal(count = 30, perBurst = 5)

        val paged = readEveryPage()

        // Including the ends and, more importantly, rows in the middle of a tie
        // group, where an ordering that was not total would drift.
        for (index in listOf(0, 1, 4, 5, 12, 17, 29)) {
            val item = paged[index]
            assertEquals(
                "positionOf disagreed with the paged order at $index",
                index,
                dao.positionOf(item.key, item.capturedAt),
            )
        }
    }

    @Test
    fun e_a_photo_that_is_gone_reports_no_position() = runBlocking {
        seedLocal(count = 3, perBurst = 1)

        assertEquals(-1, dao.positionOf("l999", 1_000_000L))
    }

    /** Walks the PagingSource the way the grid does: one page, then the next. */
    private suspend fun readEveryPage(): List<GalleryItem> {
        val source = dao.pagingTimeline()
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
            params = PagingSource.LoadParams.Append(
                key = next,
                loadSize = pageSize,
                placeholdersEnabled = false,
            )
        }
        return all
    }

    private suspend fun seedLocal(count: Int, perBurst: Int) {
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
                    // Everything in one burst shares a capture time.
                    capturedAt = base - (i - 1) / perBurst * 1_000L,
                    mimeType = "image/jpeg",
                    durationMs = null,
                    width = 100,
                    height = 100,
                    orientation = 0,
                    sha256 = "sha-$i",
                    state = AssetState.VERIFIED,
                    remoteId = null,
                ),
            )
        }
    }

    private fun remote(id: String, sha256: String, capturedAt: Long) = RemoteAsset(
        id = id,
        sha256 = sha256,
        sizeBytes = 1024,
        mimeType = "image/jpeg",
        filename = "$id.jpg",
        capturedAt = capturedAt,
        uploadedAt = capturedAt,
        width = 100,
        height = 100,
        durationMs = null,
        orientation = 0,
        deleted = false,
        updatedAt = capturedAt,
    )
}
