package com.interstellar.proxy.bg

import com.interstellar.proxy.core.PlatformFactSink
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Android side is only allowed to *report facts*; every decision lives in the
 * core's shared policy. These tests pin exactly that boundary: levels and booleans
 * are forwarded unchanged, one fact produces exactly one call (no Kotlin-invented
 * extra actions such as a release/reconnect), and after detach nothing is
 * delivered at all.
 *
 * `install()` is not exercised here — it needs a real Context; its two sources are
 * covered by the emulator acceptance run.
 */
class PlatformFactsTest {

    private class RecordingSink : PlatformFactSink {
        val events = CopyOnWriteArrayList<String>()

        override fun memoryTrim(level: Int) {
            events += "trim:$level"
        }

        override fun setAppForeground(foreground: Boolean) {
            events += "foreground:$foreground"
        }

        override fun setScreenOn(on: Boolean) {
            events += "screen:$on"
        }

        override fun reportDeviceWake() {
            events += "wake"
        }
    }

    @After
    fun tearDown() {
        PlatformFacts.detach()
    }

    private fun awaitAtLeast(sink: RecordingSink, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.events.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        check(sink.events.size >= count) {
            "expected >= $count events, got ${sink.events}"
        }
    }

    private fun settle() = Thread.sleep(150)

    @Test
    fun `attach immediately reports the current screen and foreground state`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        check(sink.events.any { it.startsWith("screen:") }) { "screen fact must be seeded: ${sink.events}" }
        check(sink.events.any { it.startsWith("foreground:") }) { "foreground fact must be seeded: ${sink.events}" }
    }

    @Test
    fun `raw android trim levels are forwarded unchanged`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        // The real Android constants, including UI_HIDDEN, passed straight through.
        val levels = listOf(5, 10, 15, 20, 40, 60, 80)
        levels.forEach { PlatformFacts.onMemoryTrim(it) }
        awaitAtLeast(sink, levels.size)

        check(sink.events.toList() == levels.map { "trim:$it" }) {
            "levels must be forwarded verbatim and in order, got ${sink.events}"
        }
    }

    @Test
    fun `ui hidden is reported as a fact and nothing else`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        val trimMemoryUiHidden = 20
        PlatformFacts.onMemoryTrim(trimMemoryUiHidden)
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.toList() == listOf("trim:$trimMemoryUiHidden")) {
            "UI_HIDDEN must not be translated into a trim/release/reconnect: ${sink.events}"
        }
    }

    @Test
    fun `one trim produces exactly one forwarded call`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onMemoryTrim(80)
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.size == 1) { "Kotlin must not invent extra actions: ${sink.events}" }
        check(sink.events[0] == "trim:80")
    }

    @Test
    fun `foreground and screen booleans are forwarded as-is`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onScreenChanged(true)
        PlatformFacts.onForegroundChanged(false)
        PlatformFacts.onForegroundChanged(true)
        awaitAtLeast(sink, 4)

        check(
            sink.events.toList() == listOf(
                "screen:false", "screen:true", "foreground:false", "foreground:true",
            ),
        ) { "facts must arrive verbatim and in order, got ${sink.events}" }
    }

    @Test
    fun `user present forwards exactly one wake fact`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        sink.events.clear()

        PlatformFacts.onUserPresent()
        awaitAtLeast(sink, 1)
        settle()

        check(sink.events.toList() == listOf("wake")) {
            "USER_PRESENT must report a wake fact and nothing more: ${sink.events}"
        }
    }

    @Test
    fun `detached sink receives nothing`() {
        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)
        PlatformFacts.detach()
        sink.events.clear()

        PlatformFacts.onMemoryTrim(80)
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onForegroundChanged(false)
        PlatformFacts.onUserPresent()
        settle()

        check(sink.events.isEmpty()) { "a detached sink must not be touched: ${sink.events}" }
    }

    @Test
    fun `facts recorded while detached are still reported when the next core attaches`() {
        PlatformFacts.detach()
        PlatformFacts.onScreenChanged(false)
        PlatformFacts.onForegroundChanged(false)
        settle()

        val sink = RecordingSink()
        PlatformFacts.attach(sink)
        awaitAtLeast(sink, 2)

        check(sink.events.contains("screen:false")) {
            "a core started while the screen is off must be told: ${sink.events}"
        }
        check(sink.events.contains("foreground:false")) {
            "a core started while the app is backgrounded must be told: ${sink.events}"
        }
    }
}
