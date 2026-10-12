# Lifecycle, services and core ownership

## Ownership map

`BoxService` **is not an Android `Service`** and there is exactly one instance per
service object:

```text
VPNService.kt:26    private val service = BoxService(this, this)   // TUN path
ProxyService.kt:11  private val service = BoxService(this, this)   // headless path
```

Both forward `onStartCommand` / `onBind` / `onDestroy`. `VPNService` tries
`super.onBind` first and falls back to `BoxService.onBind`.

| Component | Owns | Does not own |
|---|---|---|
| `BoxService` | lifecycle, core, TUN fd reference, fact session, notification, `SERVICE_CLOSE` receiver, AIDL binder, `CoreLifecycle` gate | the `VpnService` builder; platform-fact collection |
| `VPNService` | `openTun()` and therefore `builder.establish()`; system-proxy state fields; `onRevoke` | core start/stop policy |
| `ProxyService` | a foreground service with no VPN, used for the disconnected URL test | any TUN |

Start direction is always one way. The UI and the quick tile call
`BoxService.start()` → `startForegroundService(VPNService)`, or `startHeadless()` →
`ProxyService`. `stop()` is a **broadcast** (`Action.SERVICE_CLOSE`), not a direct
call. The core never starts an Android component: it can only ask the host to stop or
reload (`CoreHost.onCoreRequestStop` / `onCoreRequestReload`).

## The TUN fd

Created in exactly one place:

```kotlin
// VPNService.openTun()
val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
service.fileDescriptor = pfd
return pfd.fd
```

Held by `BoxService.fileDescriptor`, closed and nulled only in `releaseCore()` step 3.
`ProxyService` cannot open a TUN: `PlatformInterfaceWrapper.openTun` defaults to
`error("invalid argument")`, and the probe config sets `includeTun=false`.

`autoDetectInterfaceControl(fd) = protect(fd)` with
`usePlatformAutoDetectInterfaceControl() = true`.

## `CoreLifecycle` — the ownership gate

The class is a small state machine whose whole purpose is that a superseded start can
never publish, and a teardown can never be observed half-done.

| Field | Meaning |
|---|---|
| `startAttempts` | monotonically increasing start identity; also the stop watermark |
| `stopStaleUpTo` | pinned to `startAttempts` by `endStop()`; refuses attempts that were alive while the slots were dirty |
| `teardownsInFlight` | > 0 blocks publishing while `core`/`factSession`/`fileDescriptor` are being cleared |
| `published` / `closed` | "a generation is live" / terminal destroy |

```text
ownsServiceLocked = !closed && attempt == startAttempts && attempt > stopStaleUpTo
```

`publish(attempt, block)` waits up to 12 s (`PUBLISH_TEARDOWN_WAIT_MS`) for
`teardownsInFlight == 0` and then runs the core assignment plus `PlatformFacts.attach`
**inside** the critical section, so the publish is atomic with respect to a teardown.
It returns false rather than publishing stale, and the caller retries up to
`MAX_START_SUPERSEDED_RETRIES = 3` or exits.

`CoreStartResult` is a three-way result (`Published`, `Superseded`, `Failed`)
specifically so a superseded attempt does not report a failure that is not one.

`releaseMutex` serializes `releaseCore` between the normal-stop path and the
failed-start path.

## Stop convergence

```text
stopService()
  -> status = Stopping
  -> notifyStopped()            (broadcast, on the calling thread)
  -> releaseCore()              (IO)
  -> main: if (lifecycle.startsSince(stopWatermark)) return   // a newer start owns it
  -> unregisterReceiver, notification.close(), status = Stopped, stopSelf()
```

`stopAndAlert` is the same release followed by an alert broadcast (not ownership-gated)
and the same wholesale-gated convergence.

`onDestroy` is terminal and unconditional: binder close, `lifecycle.close()`,
unregister, notification close, then `releaseCore()` on IO.

### AI caution: `notifyStopped()` precedes `releaseCore()`

The broadcast is sent *before* the core, TUN and facts are released, so
`AppViewModel.markStopped()` and `Settings.tileActive = false` become visible while the
TUN may still be alive for the duration of the release (up to the two 5 s drain waits
plus close). The class's own comment argues the *opposite* invariant for the
notification, which is why the notification is gated and the broadcast is not. This is
a deliberate ordering, not an accident — but a reader who assumes "Stopped means the
TUN is gone" is wrong. Fixing it would require moving the broadcast after
`releaseCore()`, which is a lifecycle change, not a cleanup.

## Notification and the release drain

`ServiceNotification` has its **own** generation gate (`NotificationPublishGate`), its
own status `CommandClient` (local-only), and its own `SCREEN_ON/OFF` receiver. A late
traffic callback from a dead generation is refused rather than drawn. `close()` must
stay last in the teardown order.

`releaseCore` tries to drain the fact bridge with two 5 s waits. If neither proves a
drain it deliberately leaves `PlatformEvents` open and still shuts the server down,
logging the tradeoff. Both branches are load-bearing.

## Settings that are read but never written

Three persisted settings have getters and setters but **zero assignment sites** in
`app/src`, so they are permanently at their defaults:

| Setting | Default | Read at | Effect |
|---|---|---|---|
| `Settings.allowBypass` | `false` | `VPNService.kt:77` | `builder.allowBypass()` never called |
| `Settings.autoRedirect` | `false` | `BoxService.kt:133` | core `OverrideOptions.autoRedirect` always false |
| `Settings.systemProxyEnabled` | `false` | `VPNService.kt:176` | the `VpnService` HTTP proxy is never armed |

`BoxService.onSetSystemProxy(enabled)` **ignores its argument** and only calls
`serviceReload()`, so a core-side "enable system proxy" request can never arm the flag
that `openTun` reads. `Settings.dynamicNotification` (default `true`) is likewise never
written, so the dynamic traffic notification cannot be turned off from the UI.

Whether that is an unfinished feature or an intentional always-off policy is not
recorded anywhere in the repository. Treat it as `UNKNOWN` and do not "fix" it by
adding a writer without a product decision.

## Evidence

`VERIFIED_SOURCE` for the ownership map, guards, stop ordering and the unwritten
settings, re-read at `942b6fe`. `VERIFIED_UNIT` for the lifecycle gate:
`CoreLifecycleTest`, `RestartOwnershipTest` (200 interleavings),
`StopConvergenceTest` (200 interleavings), `NotificationPublishGateTest`,
`TeardownFailureTest` — all executed and passing on 2026-10-12, see
`modules/tests-evidence.md`.

`NEEDS_DEVICE`: actual `onRevoke`, OEM process killing, and Doze-driven behaviour.
