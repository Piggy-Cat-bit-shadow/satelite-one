package com.interstellar.proxy.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Initialization-order guard for `AppViewModel`'s static-group refresh state.
 *
 * Kotlin runs property initializers and `init` blocks in source order. The class has
 * `init { refreshStaticGroups() }`, which runs synchronously during construction, and
 * `refreshStaticGroups()` reads its bookkeeping before launching anything. A field declared BELOW
 * that init block is still null at that point (the JVM field has not been assigned yet), so the
 * read throws `NullPointerException` inside the constructor and the process dies before the first
 * frame - the 0.1.50 "opens and immediately stops" regression.
 *
 * The ViewModel cannot be constructed in a plain JVM test here (it is an `AndroidViewModel`, and
 * this host has neither Robolectric nor an `androidTest` source set), so this test pins the source
 * shape, the same technique [AppViewModelWiringTest] uses for wiring that no behavioural test can
 * reach. It fails on the 0.1.50 order and passes only when both fields are declared above the init
 * block.
 */
class AppViewModelInitOrderTest {

    private val source: String =
        File("src/main/java/com/interstellar/proxy/ui/AppViewModel.kt").readText()

    @Test
    fun `static-group refresh state is declared before the init block that reads it`() {
        val initIndex = source.indexOf("\n    init {")
        assertTrue(
            "AppViewModel no longer declares a class-level `init` block; if that moved, confirm " +
                "nothing else calls refreshStaticGroups() during construction and update this guard",
            initIndex >= 0,
        )
        listOf(
            "private var staticGroupsRevision",
            "private val staticGroupsRefresh",
        ).forEach { declaration ->
            val declarationIndex = source.indexOf(declaration)
            assertTrue("AppViewModel no longer declares `$declaration`; the guard moved", declarationIndex >= 0)
            assertTrue(
                "`$declaration` is declared BELOW `init { ... }`. Kotlin initializes fields and init " +
                    "blocks in source order, and init calls refreshStaticGroups() synchronously, so " +
                    "that field is null there and the constructor throws NPE on launch (0.1.50 crash). " +
                    "Move the declaration above the init block.",
                declarationIndex < initIndex,
            )
        }
    }
}
