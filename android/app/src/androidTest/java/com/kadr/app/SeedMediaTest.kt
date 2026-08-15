package com.kadr.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion of behaviour — a fixture. Leaves a handful of images in the
 * emulator's MediaStore so the app can be driven by hand (or screenshotted)
 * against something real.
 *
 *   adb shell am instrument -w -e class com.kadr.app.SeedMediaTest \
 *     com.kadr.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SeedMediaTest {

    @Test
    fun plants_demo_media_and_leaves_it_there() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val stamp = System.currentTimeMillis()

        val planted = listOf(
            TestMedia.seedJpeg(resolver, "kadr_demo_beach_$stamp.jpg", 1280, 960),
            TestMedia.seedJpeg(resolver, "kadr_demo_portrait_$stamp.jpg", 960, 1280),
            TestMedia.seedJpeg(resolver, "kadr_demo_wide_$stamp.jpg", 1920, 820),
            TestMedia.seedJpeg(
                resolver,
                "kadr_demo_large_$stamp.jpg",
                2048,
                1536,
                padTo = 5L * 1024 * 1024,
            ),
        )

        assertTrue("Nothing was planted", planted.isNotEmpty())
    }
}
