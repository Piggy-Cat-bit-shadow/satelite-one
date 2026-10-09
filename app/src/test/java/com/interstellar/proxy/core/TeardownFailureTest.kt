package com.interstellar.proxy.core

import org.junit.Test
import kotlinx.coroutines.CancellationException

/**
 * Teardown failure discipline (P0-C).
 *
 * The native teardown steps in [SingBoxCore] used `runCatching`, which has two problems
 * this pins down:
 *
 *  1. it swallows [CancellationException], so a teardown that was cancelled mid-way is
 *     indistinguishable from one that completed - the caller then reports "released"
 *     for resources that are still live;
 *  2. it erases the distinction between "closed" and "failed to close" at the point
 *     where the caller could still do something about it.
 */
class TeardownFailureTest {

    @Test
    fun `a failing step is reported as null, not as success`() {
        var ran = false
        val result = closeReportingFailure<Unit>("platformEvents.close") {
            ran = true
            throw IllegalStateException("native handle already freed")
        }
        check(ran) { "the step must actually run" }
        check(result == null) { "a failed teardown step must not report a value" }
    }

    @Test
    fun `a successful step returns its value`() {
        val result = closeReportingFailure("closeService") { 42 }
        check(result == 42) { "a successful step must return its value, got $result" }
    }

    @Test
    fun `cancellation propagates instead of being reported as a completed teardown`() {
        // The old `runCatching` shape swallowed this and the caller went on to claim the
        // resource had been released.
        var thrown: CancellationException? = null
        try {
            closeReportingFailure<Unit>("server.close") {
                throw CancellationException("teardown cancelled")
            }
        } catch (e: CancellationException) {
            thrown = e
        }
        check(thrown != null) { "CancellationException must not be swallowed by teardown" }
    }

    @Test
    fun `an error is not rethrown as a failure of the caller's own logic`() {
        // A step failure must not escape as an exception: the remaining teardown steps
        // still have to run (fd, monitor, server), so the helper reports and returns.
        val after = closeReportingFailure("platformEvents.close") {
            throw OutOfMemoryError("simulated native failure")
        }
        check(after == null)
    }
}
