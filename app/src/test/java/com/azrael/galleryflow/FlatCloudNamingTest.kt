package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class FlatCloudNamingTest {
    private val sha = "abcd1234".repeat(8)

    @Test fun allAlbumsAreStoredUnderOneDirectory() {
        val folder = "/From: Other Applications/TestApp-123/Kyora"
        val first = FlatCloudNaming.fileName("kiutaku", "gallery:123-page:0", sha, "0001.jpg")
        val second = FlatCloudNaming.fileName("kiutaku", "gallery:456-page:0", sha, "0001.jpg")
        assertNotEquals(first, second)
        assertEquals(folder + "/" + first, FlatCloudNaming.path(folder, first))
        assertEquals(folder + "/" + second, FlatCloudNaming.path(folder, second))
        assertFalse(first.contains("/"))
        assertFalse(second.contains("/"))
    }

    @Test fun filenamesSeparateSourcesAndPreserveExtensions() {
        val image = FlatCloudNaming.fileName("4khd", "1234", sha, "0001.webp")
        val video = FlatCloudNaming.fileName("cosplaytele", "1234", sha, "0001.mp4")
        assertTrue(image.endsWith(".webp"))
        assertTrue(video.endsWith(".mp4"))
        assertNotEquals(image, video)
    }

    @Test fun invalidHashIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FlatCloudNaming.fileName("kiutaku", "abc", "broken", "a.jpg")
        }
    }
}
