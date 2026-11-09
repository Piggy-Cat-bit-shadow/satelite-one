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

    /**
     * The shape the pinned core actually puts on the wire.
     *
     * `CommandClient.refreshRemoteProviders` marshals the protobuf response with Go's
     * `encoding/json`, which uses the generated `json:"..."` tags: `pending_tags`,
     * `restart_required`, `box_changed`, `requested_revision`, `applied_revision`. This fixture is
     * copied from that shape (and from the fixed AAR's recorded `provider refresh result` line), not
     * from a hand-written camelCase summary. A client that decodes only camelCase silently drops the
     * partial-application fields, which makes a rejected group look like a clean publish.
     */
    @Test
    fun `decodes the snake_case wire shape the pinned core returns`() {
        val summary = ProviderRefresh.parse(
            """
            {"results":[{"tag":"pp","status":"published","requested_revision":4,"applied_revision":4,
              "pending_tags":["node-a","node-b"],"restart_required":true,"reason":"group rejected"}],
             "cancelled":false,"box_changed":true}
            """.trimIndent(),
        )
        requireNotNull(summary)
        assertEquals(true, summary.boxChanged)
        val result = summary.results.single()
        assertEquals(listOf("node-a", "node-b"), result.pendingTags)
        assertEquals(true, result.restartRequired)
        assertEquals(4L, result.requestedRevision)
        assertEquals(4L, result.appliedRevision)
        val counts = ProviderRefresh.counts(summary)
        assertEquals(2, counts.pendingTags)
        assertEquals(1, counts.restartRequired)
        assertTrue(ProviderRefresh.partlyApplied(counts))
    }

    @Test
    fun `decodes the snake_case cancellation flag`() {
        val summary = ProviderRefresh.parse("""{"results":[],"cancelled":true,"box_changed":false}""")
        requireNotNull(summary)
        assertEquals(true, summary.cancelled)
        assertEquals(false, summary.boxChanged)
    }

    /**
     * The camelCase spelling was only ever the client's own test fixture. It is kept decodable so an
     * older local summary (or a future protojson-based core) does not turn into an empty success,
     * but it is not the shape this client is verified against.
     */
    @Test
    fun `keeps the legacy camelCase names decodable`() {
        val summary = ProviderRefresh.parse(
            """{"results":[{"tag":"pp","status":"published","pendingTags":["a"],"restartRequired":true}],"cancelled":true,"boxChanged":true}""",
        )
        requireNotNull(summary)
        assertEquals(listOf("a"), summary.results.single().pendingTags)
        assertEquals(true, summary.results.single().restartRequired)
        assertEquals(true, summary.cancelled)
        assertEquals(true, summary.boxChanged)
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
