package com.interstellar.proxy.data

import com.interstellar.proxy.data.SubscriptionUpdateCoordinator.ActiveConfigAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
