package com.interstellar.proxy.ui

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import java.io.File
import org.junit.Test

/**
 * The sing-box url-test lifecycle belongs to the RPC call, and nothing else.
 *
 * This is the property the client kept breaking. Each earlier version decided for itself whether
 * a run had finished - with a `done >= total` gate over the outbounds stream, then a 15 s + 10 s
 * settle poll after the RPC had already returned - and each time the client waited for a
 * condition the kernel was never going to satisfy, so every run burned its whole cap.
 *
 * The official client does none of this: `GroupsViewModel.urlTestGroup` adds the tag to
 * `testingGroups`, awaits `CommandClient.urlTest(tag)`, and removes it in `finally`. The call is
 * the lifecycle.
 *
 * The runtime half of that cannot be unit-tested without a core and a device, so - as elsewhere
 * in this suite - the structural half is pinned against the real source. These tests fail if the
 * client-side lifecycle is reintroduced.
 */
class UrlTestLifecycleTest {

    private val viewModelSource =
        File("src/main/java/com/interstellar/proxy/ui/AppViewModel.kt").readText()

    /**
     * Source with comments removed.
     *
     * The whole-file assertions below look for the names of things that were deliberately
     * removed, and the code explains those removals in comments - which mention the very names
     * being asserted absent. Checking prose as if it were code made the guard fail on its own
     * documentation.
     */
    private val viewModelCode = viewModelSource.lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * The body of a declaration, brace-matched or not.
     *
     * Some of these are expression-bodied (`= eligibleUrlTestTags(target)...`), which have no
     * braces of their own. Searching for the next `{` then ran on into the FOLLOWING function and
     * judged unrelated code - so the expression form is taken up to the blank line that ends it.
     */
    private fun bodyOf(signature: String): String {
        val start = viewModelSource.indexOf(signature)
        check(start >= 0) { "not found in AppViewModel.kt: $signature" }
        val brace = viewModelSource.indexOf('{', start)
        val equals = viewModelSource.indexOf('=', start)
        if (equals in 0 until brace) {
            val end = viewModelSource.indexOf("\n\n", equals)
            return viewModelSource.substring(start, if (end > 0) end else viewModelSource.length)
        }
        var depth = 0
        var i = brace
        val from = brace
        while (i < viewModelSource.length) {
            when (viewModelSource[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return viewModelSource.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after: $signature")
    }

    // ---- the RPC is the lifecycle ------------------------------------------

    @Test
    fun `the run function does not wait for the stream after the RPC returns`() {
        val body = bodyOf("private suspend fun runKernelUrlTest(")
        check(!body.contains("delay(")) {
            "runKernelUrlTest must not poll: the RPC returning IS the end of the client's " +
                "involvement. Found a delay in:\n$body"
        }
        check(!body.contains("testStartEpoch")) {
            "runKernelUrlTest must not gate on the stamp epoch"
        }
        check(body.contains("CommandTarget.standaloneClient().urlTest(target)")) {
            "the run must be exactly one client call"
        }
    }

    @Test
    fun `testing is cleared in a finally so an exception cannot strand the spinner`() {
        val body = bodyOf("private suspend fun runKernelUrlTest(")
        val finallyIndex = body.indexOf("finally")
        check(finallyIndex >= 0) { "the manual run must clear its state in a finally" }
        check(body.substring(finallyIndex).contains("_testing.value = false")) {
            "a throwing RPC must still end the testing state"
        }
    }

    @Test
    fun `no client-side completion inference survives anywhere`() {
        // The specific shapes that used to decide a run was over on the client's terms.
        // Comments are excluded: the code explains these removals by name.
        check(!viewModelCode.contains("PER_NODE_TEST_TIMEOUT_MS")) {
            "the client-side per-node timeout constant is back"
        }
        check(!viewModelCode.contains("awaitUrlTestSettled")) {
            "the settle poll is back; the RPC already returned, so it can only add latency"
        }
        check(!viewModelCode.contains("kernelTestManual")) {
            "the manual/smart flag that gated completion is back; the `manual` parameter " +
                "belongs to the call, not to shared mutable state read from the stream"
        }
    }

    @Test
    fun `the outbounds stream cannot end a run`() {
        val handler = bodyOf("override fun updateOutbounds(")
        check(!handler.contains("_testing.value")) {
            "the stream cannot know whether the RPC is still in flight, so it must never end " +
                "the testing state"
        }
        check(!handler.contains("testStartEpoch = 0")) {
            "the stream must not clear the epoch; that was the completion gate"
        }
    }

    @Test
    fun `the mutex serializes but carries no completion meaning`() {
        val body = bodyOf("private suspend fun runKernelUrlTest(")
        check(body.contains("kernelUrlTestMutex.withLock")) {
            "manual and smart runs must still be serialized against each other"
        }
        val lockBody = body.substringAfter("kernelUrlTestMutex.withLock")
        check(!lockBody.contains("while")) {
            "the lock must not contain a wait loop"
        }
    }

    // ---- smart mode must not touch the manual UI ---------------------------

    @Test
    fun `the smart-mode auto-test cannot drive the manual spinner`() {
        val marker = "Smart-mode auto-test."
        val index = viewModelSource.indexOf(marker)
        check(index >= 0) { "the smart-mode auto-test call site was not found" }
        val call = viewModelSource.substring(index, index + 700)
        check(call.contains("manual = false")) {
            "the smart run must not touch the manual test's UI state"
        }
        check(call.contains("runKernelUrlTest(")) {
            "the smart run must share the mutex with the manual run rather than calling the " +
                "client directly"
        }
    }

    // ---- one target for the RPC, the denominator and the summary -----------

    @Test
    fun `the manual entry resolves the target once and reuses it`() {
        val body = bodyOf("fun urlTest(groupTag: String)")
        check(body.contains("val target = urlTestTarget(groupTag)")) {
            "the target must be resolved once"
        }
        check(body.contains("runKernelUrlTest(target = target, manual = true)")) {
            "the RPC must use that target"
        }
        check(body.contains("beginTestRun(target)")) {
            "the progress/summary bookkeeping must use the SAME target"
        }
    }

    /**
     * Case C from the task: the progress denominator is the target's own testable members.
     *
     * The regression was a denominator taken from a different set than the numerator - the
     * `proxy` group, or the accumulated delays map - so the two could never meet and the bar
     * stalled short of N/N.
     */
    @Test
    fun `the denominator comes from the target's own members, never the group or the cache`() {
        val body = bodyOf("private fun urlTestTotal(")
        check(body.contains("eligibleUrlTestTags(target)")) {
            "the total must be the target's eligible set"
        }
        check(!body.contains("_delays")) {
            "a historical delay cache is not this run's member set"
        }
        check(!body.contains("GROUP_TAG")) {
            "a hard-coded group tag is not this run's target"
        }
    }

    @Test
    fun `a target whose size cannot be established shows no numbers rather than a guess`() {
        val body = bodyOf("private fun urlTestTotal(")
        check(body.contains("takeIf { it > 0 }")) {
            "0 eligible nodes must yield null (show 测速中…), not a 0/N denominator"
        }
    }

    @Test
    fun `case C - auto and proxy groups with different sizes resolve to the target's own set`() {
        // GROUP_TAG 45 items, AUTO_TAG 38: the run targets AUTO_TAG, so the denominator is 38.
        val proxy = CoreGroup(
            tag = MinimalConfigBuilder.GROUP_TAG,
            type = "selector",
            selected = MinimalConfigBuilder.AUTO_TAG,
            items = buildList {
                add(CoreGroupItem(MinimalConfigBuilder.AUTO_TAG, "urltest"))
                repeat(44) { add(CoreGroupItem("proxy-node-$it", "vless")) }
            },
        )
        val auto = CoreGroup(
            tag = MinimalConfigBuilder.AUTO_TAG,
            type = "urltest",
            selected = null,
            items = (0 until 38).map { CoreGroupItem("auto-node-$it", "vless") },
        )
        val groups = listOf(proxy, auto)

        val target = resolveUrlTestTarget(MinimalConfigBuilder.AUTO_TAG, groups)
        check(target == MinimalConfigBuilder.AUTO_TAG) { "the auto tab must test auto" }

        val eligible = eligibleUrlTestTags(groups.first { it.tag == target }.items)
        check(eligible.size == 38) {
            "progress total must be the target's testable members (38), never the proxy " +
                "selector's item count (45); got ${eligible.size}"
        }
    }
}
