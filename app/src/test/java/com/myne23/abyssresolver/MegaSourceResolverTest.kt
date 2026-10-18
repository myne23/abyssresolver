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

    @Test fun parsesVerifiedMyTsumiVectorOne() {
        assertEquals(
            MegaSource("ayxnkbRA", "27Ymo-aIa5oxVtSIB8qotfVpYdu2GBv-zJpQ-7KjXWQ"),
            MegaSourceResolver.parse("https://mega.nz/embed/ayxnkbRA#27Ymo-aIa5oxVtSIB8qotfVpYdu2GBv-zJpQ-7KjXWQ")
        )
    }

    @Test fun parsesVerifiedMyTsumiVectorTwo() {
        assertEquals(
            MegaSource("EMAg2TzC", "7-Ufo4OjbEhs2ZrrgRVDbPQ5SGzQy_xypmwzXDETwaE"),
            MegaSourceResolver.parse("https://mega.nz/embed/EMAg2TzC#7-Ufo4OjbEhs2ZrrgRVDbPQ5SGzQy_xypmwzXDETwaE")
        )
    }

    @Test fun rejectsNonMegaAndIncompleteLinks() {
        assertNull(MegaSourceResolver.parse("https://example.com/video.mp4"))
        assertNull(MegaSourceResolver.parse("https://mega.nz/file/AbCdEf123"))
    }
}
