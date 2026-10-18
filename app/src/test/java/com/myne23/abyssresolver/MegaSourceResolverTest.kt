package com.myne23.abyssresolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MegaSourceResolverTest {
    private val key = "A".repeat(43)

    @Test fun parsesEmbedHandleAndKey() {
        assertEquals(MegaSource("AbCdEf123", key), MegaSourceResolver.parse("https://mega.nz/embed/AbCdEf123#$key"))
    }

    @Test fun parsesFileHandleAndKey() {
        assertEquals(MegaSource("AbCdEf123", key), MegaSourceResolver.parse("https://mega.nz/file/AbCdEf123#$key"))
    }

    @Test fun parsesLegacyBangLink() {
        assertEquals(MegaSource("AbCdEf123", key), MegaSourceResolver.parse("https://mega.nz/#!AbCdEf123!$key"))
    }

    @Test fun parsesBangEmbedLink() {
        assertEquals(MegaSource("AbCdEf123", key), MegaSourceResolver.parse("https://mega.nz/embed#!AbCdEf123!$key"))
    }

    @Test fun rejectsNonMegaAndIncompleteLinks() {
        assertNull(MegaSourceResolver.parse("https://example.com/video.mp4"))
        assertNull(MegaSourceResolver.parse("https://mega.nz/file/AbCdEf123"))
    }
}
