package com.myne23.abyssresolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbyssSourceSelectionTest {
    private fun source(
        label: String, resId: Int, size: Long, partSize: Long = 0L,
        status: Boolean = true, path: String = "file-$resId-$size.mp4",
        url: String = "https://example.test"
    ) = AbyssSourceOption(label, resId, size, partSize, status, path, url)

    private val realSources = listOf(
        source("480p", 3, 126328577L, path = "a/8/1/0d72a65287345e9aac0db5d225b84.126328577.3"),
        source("720p", 4, 245926117L, path = "b/5/c/e7875ff5a775f2c4f185ba35574fe.245926117.4"),
        source("1080p", 5, 647790084L, partSize = 324009984L, path = "f/2/8/c71e2493bb14becdd2db1a3de8f56.647790084.5"),
        source("1080p", 5, 327032461L, path = "4/0/3/a8b68c0c71034457b1a261c66754d.327032461.5")
    )

    @Test fun requestedResolutionsChooseMatchingCompleteFile() {
        assertEquals(126328577L, AbyssSourceSelection.select(realSources, "3").selected!!.size)
        assertEquals(245926117L, AbyssSourceSelection.select(realSources, "4").selected!!.size)
        assertEquals(327032461L, AbyssSourceSelection.select(realSources, "5").selected!!.size)
        assertEquals(327032461L, AbyssSourceSelection.select(realSources, "best").selected!!.size)
        assertEquals(3, AbyssSourceSelection.select(realSources, "best").validCount)
    }

    @Test fun onlyFragmentedSourceIsUnsupported() {
        val result = AbyssSourceSelection.select(listOf(realSources[2]), "best")
        assertNull(result.selected)
        assertEquals(0, result.validCount)
        assertEquals("unsupported", result.failureCode)
        assertTrue(result.failureMessage!!.contains("Este contenido solo está disponible"))
        assertTrue(result.failureMessage!!.contains("This content is only available"))
    }

    @Test fun existingOneFilePerQualityStillWorks() {
        val oldEmbed = listOf(
            source("480p", 3, 100L), source("720p", 4, 200L), source("1080p", 5, 300L)
        )
        assertEquals(100L, AbyssSourceSelection.select(oldEmbed, "3").selected!!.size)
        assertEquals(200L, AbyssSourceSelection.select(oldEmbed, "4").selected!!.size)
        assertEquals(300L, AbyssSourceSelection.select(oldEmbed, "5").selected!!.size)
    }

    @Test fun missingPathOrUrlIsExcluded() {
        val missingPath = source("480p", 3, 100L, path = "")
        val missingUrl = source("720p", 4, 200L, url = "")
        val result = AbyssSourceSelection.select(listOf(missingPath, missingUrl, source("1080p", 5, 300L)), "best")
        assertEquals(1, result.validCount)
        assertEquals(300L, result.selected?.size)
    }

    @Test fun nineSourceKeysAreRetainedForDiagnostics() {
        val option = source("480p", 3, 100L).copy(
            keys = listOf("label", "res_id", "size", "codec", "status", "path", "url", "partSize", "sub")
        )
        assertEquals(9, option.keys.size)
        assertTrue(option.keys.contains("partSize"))
        assertTrue(option.keys.contains("sub"))
        val options = listOf(option)
        val result = AbyssSourceSelection.select(options, "3")
        val summary = AbyssSourceSelection.diagnosticSummary(options, result)
        assertTrue(summary.contains("sourceCountBefore=1 sourceCountAfter=1"))
        option.keys.forEach { key -> assertTrue("Missing diagnostic key: $key", summary.contains(key)) }
        assertTrue(summary.contains("reason=elegida (selected)"))
    }

    @Test fun requestedQualityFallsBackDownward() {
        val result = AbyssSourceSelection.select(listOf(source("480p", 3, 100L), source("720p", 4, 200L)), "5")
        assertNotNull(result.selected)
        assertEquals(4, result.selected!!.resId)
    }

    @Test fun duplicatesPreferWorkingThenLargerSource() {
        val options = listOf(
            source("1080p", 5, 900L, status = false),
            source("1080p", 5, 300L, status = true),
            source("1080p", 5, 700L, status = true)
        )
        assertEquals(700L, AbyssSourceSelection.select(options, "5").selected!!.size)
    }
}
