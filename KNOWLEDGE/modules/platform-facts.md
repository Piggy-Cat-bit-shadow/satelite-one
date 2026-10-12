# Platform facts (`bg/PlatformFacts.kt`, `bg/ScreenFactState.kt`)

`PlatformFacts` is the **only** writer and forwarder of Android platform facts to the
core. Nothing else in the app may report screen, foreground, trim or user-present to
libbox. `ServiceNotification` has its own `SCREEN_ON/OFF` receiver, but it drives the
notification's status client — never the core fact path.

## What it is, and what it is not

| Type | Direction | Reached from |
|---|---|---|
| `bg/PlatformFacts.kt` | **app → core** facts (`PlatformFactSink` → `PlatformEvents`) | `InterstellarApplication.onCreate` → `install()`, `onTrimMemory`, one `BroadcastReceiver`, `ActivityLifecycleCallbacks` |
| `bg/PlatformInterfaceWrapper.kt` | **core → app** capabilities (libbox `PlatformInterface`) | the core calls it: `openTun`, `findConnectionOwner`, `getInterfaces`, `readWIFIState`, `localDNSTransport` |

They are opposite directions, not two implementations of one idea. Neither wraps the
other. Confusing them is the most likely first mistake; `modules/vpn-network.md` owns
the wrapper.

## Fact classes: level, edge, or one-shot

| Fact | Android shape | Forwarded as | Core call | Default before any observation |
|---|---|---|---|---|
| Screen | `ACTION_SCREEN_ON/OFF`, plus one `PowerManager.isInteractive` seed | **level** | `setScreenOn(on)` | **ON** (`ScreenFactState.on = true`) |
| App foreground | `ActivityLifecycleCallbacks` → `ActivityForegroundTracker` | **level** | `setAppForeground(bool)` | **false, deliberately** |
| Memory trim | `Application.onTrimMemory(level)` | **level, unmodified integer** | `memoryTrim(level)` | none |
| User present / unlock | `ACTION_USER_PRESENT` | **one-shot call, not a level** | `reportDeviceWake()` | none |

The trim path is a single expression with no threshold, mapping or branch anywhere:

```kotlin
// InterstellarApplication.kt
override fun onTrimMemory(level: Int) { super.onTrimMemory(level); PlatformFacts.onMemoryTrim(level) }
// PlatformFacts.kt
fun onMemoryTrim(level: Int) = synchronized(lock) { current?.let { live -> enqueue(live) { sink -> sink.memoryTrim(level) } } }
```

The foreground default is `false` on purpose: a process can be started with no
Activity at all (quick-settings tile, background service), and "we have not seen an
`onStart` yet" is not evidence of being in the foreground. The screen default is `true`
for a different reason: the pre-seed value must not fabricate a sleep that triggers a
core pause. Both are deliberate; do not "make them consistent".

## The seed: epoch first, platform read second

The startup screen read must not overwrite a newer observation that raced it. The
observation epoch is captured **before** the platform read, and the write is refused
if the epoch moved:

```kotlin
internal fun seedInitialScreen(readInteractive: () -> Boolean): Boolean {
    val observedBefore = screen.observationEpoch()
    screenSeedHook?.invoke()
    val interactive = readInteractive()
    return screen.seedSnapshot(interactive, observedBefore)
}
```

`ScreenFactState.onChanged(value)` bumps `observationEpoch` on **every** observation,
including a same-value one, and only then reports whether the value changed. So a
`SCREEN_ON` that arrives while the seed's `isInteractive` call is in flight invalidates
the seed even though the value is identical. A "optimization" that only bumps the
epoch when the value changes reopens the stale-seed bug.

## Lock boundary and listener ordering

- `private val lock = Any()` guards the fact fields, the session identity and enqueue
  ordering, and is **never** held across a listener call or a native call.
- `onForegroundChanged` is brace-bodied specifically so `dispatchForegroundListeners`
  runs *after* the `synchronized` block.
- The listener list is copied under the lock and invoked outside it.
- `addForegroundListener` registers under the lock, then replays the canonical value
  outside it.
- Delivery to the core runs on a separate single worker over `Channel(UNLIMITED)` on
  `Dispatchers.Default`, with a per-task re-check (`if (current?.id == target.id)`) so
  a detached bridge never receives a native call.

Ordering across nested notifications uses a ticket plus a watermark:

```text
foregroundSequence  : next ticket to hand out
foregroundDelivered : highest ticket fully delivered; -1 means nothing delivered yet
insideListener      : ThreadLocal; a nested notification delivers inline with no ticket
```

`awaitForegroundTurn` yields until `foregroundDelivered == ticket - 1`, and the
watermark advances with `maxOf(...)`. A listener that blocks therefore stalls every
later foreground notification — the UI's listener performs command-client
connect/disconnect, so this is not theoretical.

## Session ownership and bounded drain

`attach(sink)` mints `Session(++nextId, sink)`, sets `current`, and enqueues the
current screen and foreground **under the same lock** that read them — so a broadcast
arriving mid-start cannot be overwritten by a staler initial value.

`detachAndDrain(expected)` unbinds only when `current.id == expected.id`, then pushes a
`CompletableDeferred` barrier through the delivery lane and waits up to 5 s. If the
drain cannot be proven, the caller **refuses** to close the bridge. The cost is one
leaked Go bridge object in a pathological case; the alternative is a native call
landing on a freed bridge.

## AI cautions

- **Do not** make foreground and screen defaults agree. One fabricates "background",
  the other would fabricate "asleep".
- **Do not** bump the observation epoch only on a value change; the epoch is a
  same-value invalidation token.
- **Do not** collapse the two `SCREEN_ON/OFF` receivers. One feeds the core's device
  axis; the other feeds the notification's own status client. `ScreenFactOrderingTest`
  enforces the separation.
- **Do not** call `ProxyCore.pause()`/`wake()` from here. `BoxService` documents that a
  second writer would fight the core's own device policy; `ScreenFactOrderingTest`
  asserts that no screen handler reaches them.
- **Do not** tidy `closePlatformEvents()` into an unconditional close.
- A new foreground listener is high-risk: it joins an ordered, reentrancy-aware lane
  whose correctness depends on every listener making progress.

## Evidence

`VERIFIED_SOURCE` for everything above, re-read at `942b6fe`. `VERIFIED_UNIT` for the
ordering, seed-wiring, lock-boundary, same-value and trim claims: the 23 JVM suites
(254 tests) were executed on 2026-10-12 in this checkout and passed — see
`modules/tests-evidence.md`.

`NEEDS_DEVICE` for OEM scheduling, Doze, real unlock delivery and real memory-trim
pressure: JVM tests model the ordering rules, they cannot produce an OEM's actual
callback sequence.
