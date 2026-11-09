package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRefreshTest {

    @Test
    fun `parses the PP01 summary and counts every status`() {
        val summary = ProviderRefresh.parse(
            """
            {"results":[
              {"tag":"p/a","status":"published"},
              {"tag":"p/b","status":"unchanged"},
              {"tag":"p/c","status":"failed","reason":"download failed"},
              {"tag":"p/d","status":"skipped"},
              {"tag":"p/e","status":"busy"}
            ],"cancelled":false,"boxChanged":false}
            """.trimIndent(),
        )
        requireNotNull(summary)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(1, counts.published)
        assertEquals(1, counts.unchanged)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.skipped)
        assertEquals(1, counts.busy)
        assertEquals(5, counts.total)
    }

    @Test
    fun `keeps an unknown status in the total instead of dropping the provider`() {
        val summary = ProviderRefresh.parse(
            """{"results":[{"tag":"p/a","status":"published"},{"tag":"p/b","status":"future-status"}]}""",
        )
        requireNotNull(summary)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(1, counts.published)
        assertEquals(2, counts.total)
    }

    @Test
    fun `reports cancellation and box replacement`() {
        val summary = ProviderRefresh.parse("""{"results":[],"cancelled":true,"boxChanged":true}""")
        requireNotNull(summary)
        assertEquals(true, summary.cancelled)
        assertEquals(true, summary.boxChanged)
        assertEquals(0, ProviderRefresh.counts(summary).total)
    }

    @Test
    fun `blank and malformed payloads are not reported as an empty success`() {
        assertNull(ProviderRefresh.parse(null))
        assertNull(ProviderRefresh.parse(""))
        assertNull(ProviderRefresh.parse("not json"))
    }

    /**
     * The core reports a document whose membership the live graph refused as `published` plus the
     * tags it could not put into service. A client that only counts `published` shows that as a
     * clean success, which is exactly the "failure must be visible" acceptance this binds.
     */
    @Test
    fun `a partly applied document is counted as pending, not as a clean success`() {
        val summary = ProviderRefresh.parse(
            """
            {"results":[
              {"tag":"pp","status":"published","pendingTags":["a","b"],"restartRequired":true}
            ]}
            """.trimIndent(),
        )
        requireNotNull(summary)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(1, counts.published)
        assertEquals(2, counts.pendingTags)
        assertEquals(1, counts.restartRequired)
        assertTrue(ProviderRefresh.partlyApplied(counts))
        assertEquals("a", summary.results.single().pendingTags.first())
    }

    @Test
    fun `a clean refresh is not partly applied`() {
        val summary = ProviderRefresh.parse(
            """{"results":[{"tag":"pp","status":"published"},{"tag":"pq","status":"unchanged"}]}""",
        )
        requireNotNull(summary)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(0, counts.pendingTags)
        assertEquals(0, counts.restartRequired)
        assertFalse(ProviderRefresh.partlyApplied(counts))
    }

    @Test
    fun `a 304-style unchanged result keeps its own count`() {
        val summary = ProviderRefresh.parse("""{"results":[{"tag":"pp","status":"unchanged"}]}""")
        requireNotNull(summary)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(0, counts.published)
        assertEquals(1, counts.unchanged)
        assertFalse(ProviderRefresh.partlyApplied(counts))
    }
}
