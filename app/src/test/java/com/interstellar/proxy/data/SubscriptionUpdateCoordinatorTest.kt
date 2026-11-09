package com.interstellar.proxy.data

import com.interstellar.proxy.data.SubscriptionUpdateCoordinator.ActiveConfigAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdateCoordinatorTest {

    @Test
    fun `updating the active subscription affects the active config`() {
        assertTrue(
            SubscriptionUpdateCoordinator.affectsActiveConfig(
                updatedIds = listOf("active"),
                activeSubscriptionId = "active",
                mixEnabled = false,
                mixSubscriptionIds = emptySet(),
            ),
        )
    }

    @Test
    fun `a mix member affects the active config while mix is on`() {
        assertTrue(
            SubscriptionUpdateCoordinator.affectsActiveConfig(
                updatedIds = listOf("member"),
                activeSubscriptionId = "active",
                mixEnabled = true,
                mixSubscriptionIds = setOf("member"),
            ),
        )
    }

    @Test
    fun `an unrelated subscription does not touch the active config`() {
        assertFalse(
            SubscriptionUpdateCoordinator.affectsActiveConfig(
                updatedIds = listOf("other"),
                activeSubscriptionId = "active",
                mixEnabled = true,
                mixSubscriptionIds = setOf("member"),
            ),
        )
        assertFalse(
            SubscriptionUpdateCoordinator.affectsActiveConfig(
                updatedIds = emptyList(),
                activeSubscriptionId = "active",
                mixEnabled = false,
                mixSubscriptionIds = emptySet(),
            ),
        )
    }

    @Test
    fun `raw active subscription still counts with mix on`() {
        assertTrue(
            SubscriptionUpdateCoordinator.affectsActiveConfig(
                updatedIds = listOf("raw"),
                activeSubscriptionId = "raw",
                mixEnabled = true,
                mixSubscriptionIds = setOf("member"),
            ),
        )
    }

    @Test
    fun `config action separates unchanged, reload and saved-only`() {
        assertEquals(ActiveConfigAction.None, SubscriptionUpdateCoordinator.action(changed = false, running = true))
        assertEquals(ActiveConfigAction.Reload, SubscriptionUpdateCoordinator.action(changed = true, running = true))
        assertEquals(ActiveConfigAction.SavedOnly, SubscriptionUpdateCoordinator.action(changed = true, running = false))
    }

    @Test
    fun `config change is a byte comparison`() {
        assertFalse(SubscriptionUpdateCoordinator.configChanged(null, null))
        assertFalse(SubscriptionUpdateCoordinator.configChanged("{}", "{}"))
        assertTrue(SubscriptionUpdateCoordinator.configChanged("{}", """{"a":1}"""))
    }

    @Test
    fun `a successful update of the active pool reports both halves`() {
        val report = SubscriptionUpdateCoordinator.report(affected = true, configUpdated = true)
        assertTrue(report.config)
        assertTrue(report.providers)
    }

    /**
     * The defect this binds: the download failed, so there is no config to apply, and the running
     * instance keeps its previous config. Its remote providers are a different failure domain, so
     * they must still be refreshed - otherwise a broken subscription URL also freezes the node
     * sources of the instance that is still working.
     */
    @Test
    fun `a failed download still refreshes the active pool's providers`() {
        val report = SubscriptionUpdateCoordinator.report(affected = true, configUpdated = false)
        assertFalse(report.config)
        assertTrue(report.providers)
    }

    @Test
    fun `an update outside the active pool reports nothing`() {
        for (configUpdated in listOf(true, false)) {
            val report = SubscriptionUpdateCoordinator.report(affected = false, configUpdated = configUpdated)
            assertFalse(report.config)
            assertFalse(report.providers)
        }
    }

    /**
     * The P1 this binds: a fetch that started while A was active and lands after the user switched
     * to B must not reload B, must not refresh B's providers, and must not report B's state as A's
     * result. A's own bytes are still stored by the caller; ownership is only about the live Box.
     */
    @Test
    fun `a late result loses ownership when another profile became active`() {
        assertFalse(
            SubscriptionUpdateCoordinator.ownsInstance(
                affectedAtStart = true,
                activeIdAtStart = "a",
                activeIdNow = "b",
            ),
        )
    }

    @Test
    fun `a result keeps ownership while the same profile stays active`() {
        for (affected in listOf(true, false)) {
            assertEquals(
                affected,
                SubscriptionUpdateCoordinator.ownsInstance(
                    affectedAtStart = affected,
                    activeIdAtStart = "a",
                    activeIdNow = "a",
                ),
            )
        }
        // An update that never concerned the active pool cannot acquire ownership by any switch.
        assertFalse(
            SubscriptionUpdateCoordinator.ownsInstance(
                affectedAtStart = false,
                activeIdAtStart = "a",
                activeIdNow = "a",
            ),
        )
        // A blank active id (no profile) is not an owner.
        assertFalse(
            SubscriptionUpdateCoordinator.ownsInstance(
                affectedAtStart = true,
                activeIdAtStart = "",
                activeIdNow = "",
            ),
        )
    }

    /**
     * A body that downloaded fine but was refused as a config must be reported as that refusal, not
     * as a config result - while the running instance's providers are still refreshed, because the
     * old config is what is serving.
     */
    @Test
    fun `a refused body suppresses the config half but not the provider half`() {
        val report = SubscriptionUpdateCoordinator.report(
            affected = true,
            configUpdated = true,
            configError = "config check failed",
        )
        assertFalse(report.config)
        assertTrue(report.providers)
        assertEquals("config check failed", report.configError)

        val clean = SubscriptionUpdateCoordinator.report(affected = true, configUpdated = true)
        assertTrue(clean.config)
        assertNull(clean.configError)
    }
}
