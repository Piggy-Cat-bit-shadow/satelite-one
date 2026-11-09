package com.interstellar.proxy.data

import com.interstellar.proxy.data.SubscriptionUpdateCoordinator.ActiveConfigAction
import com.interstellar.proxy.data.SubscriptionUpdateCoordinator.InstanceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdateCoordinatorTest {

    private fun snapshot(
        activeId: String = "a",
        mixEnabled: Boolean = false,
        mixIds: Set<String> = emptySet(),
        generation: Long = 1L,
        revision: Long = 1L,
    ) = InstanceSnapshot(
        activeSubscriptionId = activeId,
        mixEnabled = mixEnabled,
        mixSubscriptionIds = mixIds,
        engineGeneration = generation,
        configRevision = revision,
    )

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
     * A body that downloaded fine but was refused as a config must be reported as that refusal, not
     * as a config result - while the running instance's providers are still refreshed, because the
     * old config is what is serving. The error must NOT suppress the config half: in a batch one
     * subscription can be refused while another one's bytes were committed and still need applying.
     */
    @Test
    fun `a refused body is reported next to the halves instead of replacing them`() {
        val report = SubscriptionUpdateCoordinator.report(
            affected = true,
            configUpdated = true,
            configError = "config check failed",
        )
        assertTrue(report.config)
        assertTrue(report.providers)
        assertEquals("config check failed", report.configError)

        val clean = SubscriptionUpdateCoordinator.report(affected = true, configUpdated = true)
        assertTrue(clean.config)
        assertNull(clean.configError)
    }

    // --- instance identity: the id alone is not an instance -----------------------------------------

    /**
     * The P1 this binds: a fetch that started while A was active and lands after the user switched
     * to B must not reload B, must not refresh B's providers, and must not report B's state as A's
     * result.
     */
    @Test
    fun `a late result loses ownership when another profile became active`() {
        assertFalse(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a"),
                current = snapshot("b"),
            ),
        )
    }

    @Test
    fun `a result keeps ownership while nothing about the instance changed`() {
        assertTrue(SubscriptionUpdateCoordinator.stillOwns(start = snapshot("a"), current = snapshot("a")))
    }

    /**
     * A -> B -> A leaves the id equal again but the Box is a different one. The engine generation
     * is what makes this case distinguishable from "nothing happened", and it is exactly the case
     * the old id-only check let through.
     */
    @Test
    fun `a round trip back to the same profile is a different instance`() {
        val start = snapshot("a", generation = 1)
        val afterRoundTrip = snapshot("a", generation = 3)
        assertFalse(SubscriptionUpdateCoordinator.stillOwns(start, afterRoundTrip))
    }

    @Test
    fun `stopping and starting the same subscription is a different instance`() {
        assertFalse(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a", generation = 4),
                current = snapshot("a", generation = 5),
            ),
        )
    }

    /**
     * A mix change rewrites the active config under the same profile id. A result that was started
     * against the previous pool must not be applied on top of it.
     */
    @Test
    fun `a mix membership change invalidates an in-flight result`() {
        assertFalse(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a", mixEnabled = true, mixIds = setOf("a")),
                current = snapshot("a", mixEnabled = true, mixIds = setOf("a", "b")),
            ),
        )
        assertFalse(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a", mixEnabled = false),
                current = snapshot("a", mixEnabled = true),
            ),
        )
    }

    /**
     * A third writer moving the config past the revision this operation committed makes the result
     * stale even when the instance is the same.
     */
    @Test
    fun `a config revision past the expected one invalidates the result`() {
        assertTrue(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a", generation = 2, revision = 7),
                current = snapshot("a", generation = 2, revision = 8),
                expectedRevision = 8,
            ),
        )
        assertFalse(
            SubscriptionUpdateCoordinator.stillOwns(
                start = snapshot("a", generation = 2, revision = 7),
                current = snapshot("a", generation = 2, revision = 9),
                expectedRevision = 8,
            ),
        )
    }

    @Test
    fun `an update outside the active pool never acquires ownership`() {
        assertFalse(
            SubscriptionUpdateCoordinator.ownsInstance(
                affectedAtStart = false,
                start = snapshot("a"),
                current = snapshot("a"),
            ),
        )
        // A blank active id (no profile) is not an owner either.
        assertFalse(
            SubscriptionUpdateCoordinator.ownsInstance(
                affectedAtStart = true,
                start = snapshot(""),
                current = snapshot(""),
            ),
        )
    }
}
