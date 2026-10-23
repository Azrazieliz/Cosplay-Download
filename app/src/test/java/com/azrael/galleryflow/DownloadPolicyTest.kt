package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun taggedGalleryIsSkippedOnlyOnExactMatch() {
        assertEquals("Blue Archive", TagExclusions.firstBlocked(
            listOf("Cosplay", "Blue Archive", "Photo Set"), "Other\nblue archive\n"))
        assertNull(TagExclusions.firstBlocked(
            listOf("Blue Archive Cosplay"), "blue archive"))
        assertEquals("ＣＯＳＰＬＡＹ", TagExclusions.firstBlocked(
            listOf("ＣＯＳＰＬＡＹ"), "cosplay"))
        assertNull(TagExclusions.firstBlocked(listOf("Cosplay"), ""))
    }

    @Test fun flatNamesRemainUniqueAcrossGalleries() {
        fun ref(gallery: String, mediaId: String, index: Int) = MediaRef(
            SourceId.KIUTAKU, mediaId, gallery, index, "https://example.com/i.png",
            "https://example.com/i.png", "https://example.com")
        val a = FlatStoragePolicy.filename(ref("gallery1", "hashA", 0), "png")
        val b = FlatStoragePolicy.filename(ref("gallery2", "hashA", 0), "png")
        val c = FlatStoragePolicy.filename(ref("gallery1", "hashB", 0), "png")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a, FlatStoragePolicy.filename(ref("gallery1", "hashA", 0), "png"))
        assertFalse(a.contains('/'))
        assertTrue(a.endsWith(".png"))
    }
}
