# UI state, command clients and presentation

## The main UI command client

`AppViewModel` owns the app-wide UI command client, created **once per `AppViewModel`**
with `viewModelScope` and the `Status`+`Groups`+`Outbounds` handlers. Its existence is
decided by `UiClientLifetime`, a pure rule set over four booleans
(`foreground`, `suspended`, `closing`, `deferringForProbe`).

### Why the verdict exists

`CommandClient.disconnect()` notifies its handlers on the *deliberate* path, which feeds
`applySocketDrop()`, which starts a 400 ms watchdog that calls `BoxService.stop()`. So
without a verdict, **backgrounding the app looks exactly like losing the socket and kills
a healthy tunnel**.

### The rules

- `connect()` subscribes to the foreground fact **first**, then reads `wantsClient()`, and
  returns early if the verdict says no. Read the verdict *after* subscribing, not before:
  subscribing can synchronously replay the canonical value and re-enter the listener.
- The 2 s reconnect poll reconnects only if `mayReconnect()`.
- `startProxy()`'s establish is gated on `wantsClient()` read **on IO after** its 250 ms
  delay — the gap a tap-then-background sequence slips through if you remove it.
- `onDisconnected`/`onConnectionError` consult `mayReportSocketLoss(probing)`.
- `disconnect()` marks `onTearingDown()` **before** calling the client's `disconnect()`.
- `runDisconnectedUrlTest` deliberately connects the shared client while `probing` is
  set, and its `finally` must clear `probing` and then `reapplyOwedSuspend()` in that
  order, with nothing suspendable before them.

`AppViewModelWiringTest` pins "every establish sits inside a verdict" by scanning
`AppViewModel.kt` as text. It will fail a refactor that is behaviourally harmless.

## The other clients

| Client | Lifetime | Why it is separate |
|---|---|---|
| main UI (`AppViewModel`) | the verdict above | app-wide status, groups, outbounds |
| connections (`ConnectionsViewModel`) | page-visibility session, `SessionGate` | page-scoped retention fix (`060b009`) |
| logs (`LogsViewModel`) | whole app session + 2 s reconnect poll | capture must outlive the Logs page |
| notification (`ServiceNotification`) | its own generation | it must publish with no UI at all |
| standalone (`CommandTarget.standaloneClient()`) | one call | ten one-shot call sites |

Do not merge these onto the main client; see `ARCHITECTURE.md`.

## Page-scoped sessions

`ConnectionsViewModel` creates a `Connections` client per page session and gates every
callback through `SessionGate`, whose generation is bumped **atomically with the clear**
under the same lock — so a callback from a closed session can never commit or revive it.

`LogsViewModel` keeps full logs in a `LogRingBuffer` (3000 entries, monotonic `version`)
and publishes a coalesced UI snapshot through `LogUiPublishGate` at ~75 ms. Two bugs are
worth remembering because both were *silent*:

- the first entry parked on the oldest line rather than the newest (`fb606bf`);
- `LaunchedEffect(logs.size)` stops firing once the ring is full, and the gate marked
  "newest" instead of the snapshot's version — so a line could be marked published and
  never displayed (`7b6aeb3`).

If you touch either, check the saturated-ring case explicitly.

## App-wide state

`AppViewModel` owns the state flows, start/stop/reload/URL-test orchestration, the 15 s
starting watchdog, the 400 ms socket-drop watchdog, `testStartEpoch`,
`kernelUrlTestMutex` and the `SERVICE_STOPPED` receiver. It does **not** own the core, the
TUN, the notification or `PlatformFacts`.

Two selection mechanisms coexist and only one is live: node selection flows through
`Settings.selectedOutboundTag`; `SubscriptionRepository.selectNode` has no caller, so
`Settings.selectedNodeId` is never written. Do not wire the dead one up without deciding
which is authoritative.

## Presentation

Compose, with a hand-rolled `NavState` and `HorizontalPager` tab shell in `MainActivity`.
`androidx.navigation` and `androidx.datastore` are declared dependencies that **no source
file imports** — do not assume either is in use.

`LocalizedNames` localizes the functional group tags (`手动选择`, `auto`) for **display
only**; the tags themselves are functional identifiers the core matches on. Never
translate the value.

## Evidence

`VERIFIED_SOURCE` for everything above, re-read at `942b6fe`. `VERIFIED_UNIT`:
`UiClientLifetimeTest` (22), `AppViewModelWiringTest` (14), `SessionGateTest` (11),
`LogUiPublishGateTest` (8), `TrafficDisplayTest` (7), `UrlTestTargetTest` (5),
`LogRingBufferTest` (18) — executed and passing on 2026-10-12.

**A green suite here is weak evidence.** The repository's own comments record that
"twelve minimal wiring breaks in `AppViewModel.kt` each produced zero red on the
230-test suite", and that the whole suite once passed with the P0-A defect restored. The
wiring half is guarded by a *source-text scan*, not by behaviour.

`NEEDS_DEVICE`: real page transitions, real subscription fetches, real proxy availability.
