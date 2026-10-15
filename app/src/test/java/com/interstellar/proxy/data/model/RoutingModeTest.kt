package com.interstellar.proxy.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The routing-mode domain model, and the two contracts everything else depends on.
 *
 * 1. **One canonical spelling per mode.** The kernel compares a route rule's `clash_mode`
 *    against the current mode case-insensitively, and also remaps an unknown spelling by
 *    equal-fold search, so several spellings "work". That tolerance is exactly why a single
 *    canonical value has to be pinned here: `MinimalConfigBuilder` declares the literal, this
 *    enum ships the same literal on the wire, and a third spelling creeping into either side
 *    would still route - until the kernel's `CalculateModeList` derived a different list from
 *    the rules than the app sends.
 *
 * 2. **`Rule` is the default.** The kernel maps an empty `default_mode` to `Rule`, so an
 *    install that never touched this control must keep the behaviour it already had. This is
 *    also why `fromWire(null)` / `fromWire("")` must not invent a mode: the callers apply the
 *    default explicitly, and an unknown *stored* value must fall back rather than propagate.
 */
class RoutingModeTest {

    @Test
    fun `wire values are the kernel literals and match the config generator's`() {
        // These three strings are the whole contract. `MinimalConfigBuilder` writes the same
        // literals into the generated config's clash_mode rules, and the kernel builds its
        // mode list from those rules - so a mismatch here is a mode the UI can select but the
        // kernel silently ignores (SetMode returns quietly for a mode not in its list).
        assertEquals("Rule", RoutingMode.RULE.wire)
        assertEquals("Global", RoutingMode.GLOBAL.wire)
        assertEquals("Direct", RoutingMode.DIRECT.wire)
        assertEquals(RoutingMode.RULE.wire, CLASH_MODE_RULE)
        assertEquals(RoutingMode.GLOBAL.wire, CLASH_MODE_GLOBAL)
        assertEquals(RoutingMode.DIRECT.wire, CLASH_MODE_DIRECT)
    }

    @Test
    fun `exactly the three product modes exist`() {
        assertEquals(
            "the product offers 规则/全局/直连 and nothing else; a fourth entry would appear " +
                "in the dashboard selector without a label or a rule to make it mean anything",
            listOf(RoutingMode.RULE, RoutingMode.GLOBAL, RoutingMode.DIRECT),
            RoutingMode.entries.toList(),
        )
    }

    @Test
    fun `default is Rule`() {
        assertSame(RoutingMode.RULE, RoutingMode.DEFAULT)
    }

    @Test
    fun `fromWire round-trips every mode`() {
        RoutingMode.entries.forEach { mode ->
            assertSame("$mode must survive its own wire value", mode, RoutingMode.fromWire(mode.wire))
        }
    }

    @Test
    fun `fromWire accepts a differently cased spelling rather than losing it`() {
        // The kernel compares case-insensitively, and a raw config may declare "direct".
        // Recognising it here means a stored/observed lower-case value normalises to the
        // canonical one instead of being thrown away as unknown.
        assertSame(RoutingMode.RULE, RoutingMode.fromWire("rule"))
        assertSame(RoutingMode.GLOBAL, RoutingMode.fromWire("global"))
        assertSame(RoutingMode.DIRECT, RoutingMode.fromWire("DIRECT"))
        assertSame(RoutingMode.GLOBAL, RoutingMode.fromWire("  Global  "))
    }

    @Test
    fun `fromWire refuses to invent a mode`() {
        // Not an error path: the kernel's mode list is derived from the ACTIVE config, and a
        // raw config may legitimately declare custom modes. Callers report an unknown value;
        // none of them may turn it into a stored mode.
        assertNull(RoutingMode.fromWire(null))
        assertNull(RoutingMode.fromWire(""))
        assertNull(RoutingMode.fromWire("   "))
        assertNull(RoutingMode.fromWire("Rule2"))
        assertNull(RoutingMode.fromWire("Proxy"))
        assertNull(RoutingMode.fromWire("直接"))
    }
}
