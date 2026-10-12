package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import org.junit.Test

/**
 * The restore decision for a node picked while the core was stopped.
 *
 * Every rejection below is a case where guessing would move a selector the user never
 * touched, or push a tag the running config does not offer. The config used in the field
 * has two selectors (`🌍 国外流量` and `🤖 AI`) that share members, so "find the group that
 * contains this tag" is not a decision - it is a coin flip.
 */
class PendingSelectionRestoreTest {

    private fun selector(tag: String, selected: String?, vararg items: String) =
        CoreGroup(tag, "selector", selected, items.map { CoreGroupItem(it, "vless") })

    private fun urltest(tag: String, selected: String?, vararg items: String) =
        CoreGroup(tag, "urltest", selected, items.map { CoreGroupItem(it, "vless") })

    @Test
    fun `restores the stored pick into the stored group`() {
        val groups = listOf(selector("国外流量", "NodeA", "NodeA", "NodeB"))
        check(pendingSelectionRestore("国外流量", "NodeB", groups) == ("国外流量" to "NodeB")) {
            "a pick the core is not on must be restored"
        }
    }

    @Test
    fun `does nothing when the group is already on that item`() {
        val groups = listOf(selector("国外流量", "NodeB", "NodeA", "NodeB"))
        check(pendingSelectionRestore("国外流量", "NodeB", groups) == null) {
            "no command should be sent when there is nothing to change"
        }
    }

    @Test
    fun `does nothing without a stored group`() {
        val groups = listOf(selector("国外流量", "NodeA", "NodeA", "NodeB"))
        // A value written before the group key existed, or a pick from another config.
        check(pendingSelectionRestore("", "NodeB", groups) == null) {
            "an unknown group must never be guessed at"
        }
    }

    @Test
    fun `does nothing when the stored group is gone`() {
        val groups = listOf(selector("AI", "NodeA", "NodeA", "NodeB"))
        check(pendingSelectionRestore("国外流量", "NodeB", groups) == null) {
            "a group that is not in the live config must leave the default in force"
        }
    }

    @Test
    fun `does nothing when the item is no longer a member`() {
        val groups = listOf(selector("国外流量", "NodeA", "NodeA", "NodeC"))
        check(pendingSelectionRestore("国外流量", "NodeB", groups) == null) {
            "a stale tag must never be pushed at the core"
        }
    }

    @Test
    fun `does nothing for a urltest group`() {
        val groups = listOf(urltest("auto", "NodeA", "NodeA", "NodeB"))
        check(pendingSelectionRestore("auto", "NodeB", groups) == null) {
            "urltest picks its own member; a manual pick there is meaningless"
        }
    }

    @Test
    fun `does nothing for a blank item`() {
        val groups = listOf(selector("国外流量", null, "NodeA", "NodeB"))
        check(pendingSelectionRestore("国外流量", "", groups) == null) {
            "a blank pick is not a pick"
        }
    }

    @Test
    fun `only the stored group is considered when several share a member`() {
        // The field config: NodeB is a legal member of BOTH selectors. The stored group
        // says which one the user actually picked in; the other must stay on its default.
        val groups = listOf(
            selector("国外流量", "NodeA", "NodeA", "NodeB"),
            selector("AI", "NodeB", "NodeA", "NodeB"),
        )
        check(pendingSelectionRestore("国外流量", "NodeB", groups) == ("国外流量" to "NodeB")) {
            "the pick must go to the group the user picked in"
        }
        check(pendingSelectionRestore("AI", "NodeB", groups) == null) {
            "the other selector is already on that member and must not be touched"
        }
        check(pendingSelectionRestore("AI", "NodeA", groups) == ("AI" to "NodeA")) {
            "the second selector restores its own pick independently"
        }
    }

    @Test
    fun `matches the group type case-insensitively`() {
        val groups = listOf(CoreGroup("G", "Selector", "A", listOf(CoreGroupItem("A", "vless"))))
        check(pendingSelectionRestore("G", "A", groups) == null) {
            "already selected - the case check must not turn this into a command"
        }
        val other = listOf(CoreGroup("G", "Selector", "A", listOf(CoreGroupItem("B", "vless"))))
        check(pendingSelectionRestore("G", "B", other) == ("G" to "B")) {
            "a mixed-case 'Selector' is still a selector"
        }
    }
}
