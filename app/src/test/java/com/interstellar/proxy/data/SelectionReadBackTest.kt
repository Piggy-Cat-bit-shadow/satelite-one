package com.interstellar.proxy.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire document `CommandClient.SelectOutboundWithRequest` returns.
 *
 * The page settles an attempt by the request id and follows the member the kernel read back; a
 * document it cannot read, or a capability answer from an old core, must not become a fake success.
 */
class SelectionReadBackTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the identity and read back the kernel sends`() {
        val document =
            """{"supported":true,"requestId":41,"appliedOutboundTag":"member-b","appliedRevision":141,"stale":true}"""
        val readBack = json.decodeFromString<SelectOutboundReadBack>(document)
        assertTrue(readBack.supported)
        assertFalse(readBack.unconfirmed)
        assertEquals(41L, readBack.requestId)
        assertEquals("member-b", readBack.appliedOutboundTag)
        assertEquals(141L, readBack.appliedRevision)
        assertTrue(readBack.stale)
    }

    @Test
    fun `an old core capability answer stays unconfirmed`() {
        val document = """{"supported":false,"unconfirmed":true,"requestId":9}"""
        val readBack = json.decodeFromString<SelectOutboundReadBack>(document)
        assertFalse(readBack.supported)
        assertTrue(readBack.unconfirmed)
        assertEquals(9L, readBack.requestId)
        assertNull(readBack.appliedOutboundTag)
        assertEquals(0L, readBack.appliedRevision)
    }

    @Test
    fun `fields this build does not know do not fail the document`() {
        val document = """{"supported":true,"requestId":7,"appliedOutboundTag":"x","futureField":1}"""
        val readBack = json.decodeFromString<SelectOutboundReadBack>(document)
        assertEquals(7L, readBack.requestId)
        assertEquals("x", readBack.appliedOutboundTag)
    }
}
