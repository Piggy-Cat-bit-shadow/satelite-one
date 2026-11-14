package com.interstellar.proxy.ui

import org.junit.Test

/**
 * When a refreshed static structure invalidates the live group snapshot.
 *
 * The node page appends provider-published members from the live half, so a snapshot left over
 * from a previous profile would display that profile's nodes under a same-tagged group of the
 * new one. The rule is revision identity, and the "already serving the new revision" case must
 * keep the snapshot: a refresh can legitimately run after the reload it belongs to.
 */
class DropLiveGroupsOnRefreshTest {

    @Test
    fun `an unchanged revision keeps the snapshot`() {
        check(!dropLiveGroupsOnRefresh(previousRevision = 7, newRevision = 7, appliedRevision = 7)) {
            "refreshing an inactive subscription or an identical write does not invalidate anything"
        }
        check(!dropLiveGroupsOnRefresh(previousRevision = 7, newRevision = 7, appliedRevision = 3)) {
            "still the same active bytes; the running core is not stale relative to them"
        }
    }

    @Test
    fun `a new revision the core has not applied drops the snapshot`() {
        check(dropLiveGroupsOnRefresh(previousRevision = 7, newRevision = 8, appliedRevision = 7)) {
            "the live snapshot still describes revision 7 while the file names 8"
        }
    }

    @Test
    fun `a new revision with no running core drops the snapshot`() {
        check(dropLiveGroupsOnRefresh(previousRevision = 7, newRevision = 8, appliedRevision = -1)) {
            "a stopped core has no snapshot of the new profile"
        }
    }

    @Test
    fun `a new revision the core already serves keeps the snapshot`() {
        check(!dropLiveGroupsOnRefresh(previousRevision = 7, newRevision = 8, appliedRevision = 8)) {
            "the refresh ran after the reload, so the snapshot can be the new instance's"
        }
    }

    @Test
    fun `the first read of a revision is compared against the placeholder`() {
        check(dropLiveGroupsOnRefresh(previousRevision = -1, newRevision = 1, appliedRevision = -1)) {
            "a cold start has no live snapshot to keep for the first revision"
        }
        check(!dropLiveGroupsOnRefresh(previousRevision = -1, newRevision = 1, appliedRevision = 1)) {
            "when the process attached to an already-running core, its snapshot belongs to this revision"
        }
    }
}
