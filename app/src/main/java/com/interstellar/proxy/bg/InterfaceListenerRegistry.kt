package com.interstellar.proxy.bg

import io.nekohasekai.libbox.InterfaceUpdateListener

/**
 * Which core currently owns the default-interface listener.
 *
 * ## The bug this exists for
 *
 * libbox hands the platform an [InterfaceUpdateListener] through
 * `PlatformInterface.startDefaultInterfaceMonitor(listener)` and takes it away through
 * `closeDefaultInterfaceMonitor(listener)`. The Android side stored that in ONE mutable
 * field on a process-wide object and answered both calls with
 * `setListener(listener)` / `setListener(null)`.
 *
 * That makes the second call a statement about the *process* rather than about the
 * caller: when a stopping core's teardown ran after a newer core had already registered
 * — headless `ProxyService` handing over to `VPNService`, or the reverse — the older
 * core's `setListener(null)` cleared the newer core's registration. The newer core then
 * never heard another default-interface change: it kept routing to a network that had
 * gone away, which is the "no network but the UI says connected" failure.
 *
 * Nothing new is needed from the native side to fix this: `closeDefaultInterfaceMonitor`
 * already *receives the listener object*, and that object's identity is the ownership
 * proof. This registry keys by exactly that identity, so a close can only ever remove the
 * registration it was given.
 *
 * ## Why identity rather than equality
 *
 * [InterfaceUpdateListener] is a gomobile-generated interface; its implementations do not
 * override `equals`/`hashCode`, so equality is already identity for them today. Relying on
 * that implicitly would be a trap for a future implementation that does override them —
 * two distinct cores' listeners that happened to compare equal would then be able to
 * remove each other. The list is scanned with `===` so the guarantee is in the code, not
 * in an assumption about a generated class.
 *
 * Pure Kotlin and no Android types, so every interleaving below is provable on the JVM.
 */
internal class InterfaceListenerRegistry<T : Any> {

    /** Registration order is preserved, and a listener is identified by reference. */
    private val listeners = mutableListOf<T>()
    private val lock = Any()

    /** Idempotent for the same listener; registering it twice does not double it. */
    fun add(listener: T) {
        synchronized(lock) {
            if (listeners.none { it === listener }) listeners.add(listener)
        }
    }

    /**
     * Remove [listener], and only [listener].
     *
     * A listener that was never registered, or already removed, is a no-op - so a
     * double close is harmless and cannot take another core's registration with it.
     */
    fun remove(listener: T) {
        synchronized(lock) {
            listeners.removeAll { it === listener }
        }
    }

    /** Whether [listener] is currently registered. */
    fun contains(listener: T): Boolean = synchronized(lock) {
        listeners.any { it === listener }
    }

    val size: Int get() = synchronized(lock) { listeners.size }

    /**
     * A snapshot of the currently registered listeners.
     *
     * Callers must dispatch from the snapshot, **outside** this class's lock: the events
     * end in a native call, and holding a Java monitor across a native call is how a
     * re-entrant callback deadlocks the platform thread.
     */
    fun snapshot(): List<T> = synchronized(lock) { listeners.toList() }
}
