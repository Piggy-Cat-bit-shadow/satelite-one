package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
