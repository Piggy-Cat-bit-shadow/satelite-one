# Hard invariants and current conventions

## Hard invariants

Each of these is a platform constraint, a release/ABI contract, or a rule that a real
defect proved. None is a style preference.

### 1. One core, one engine. No selector. (`d2a2e98`, `d628ef0`)

`CoreEngines.create()` returns `SingBoxCore` unconditionally. The old multi-core/sidecar
design (mihomo + Xray + a JNI bridge + geodata assets) was deleted. Recreating a
selector, or an app-owned sidecar, invalidates the entire per-ABI AAR/provenance/release
gate chain.

**Residue that must not be read as capability:** `.gitignore` still mentions sidecar
cores and a `tools/fetch_cores.py` that no longer exists; `RawConfigFormat.XRAY` still
enumerates Xray for *detection* only; comments in `AppViewModel.kt` and `NodesPage.kt`
still say "sidecar"/"mihomo"; `android-ci.yml` still shows an example dispatch at a
superseded pin. The claim is true of the code, not of `grep`.

### 2. Android forwards raw facts; the core owns policy. (`a3d2321`, `a5a6783`)

The app does not map trim levels, does not synthesize pause/wake/reconnect, and does not
duplicate the core's device policy. `ProxyCore.pause()` / `wake()` are declared and
**must stay uncalled** — they are left in the interface as the core's own surface.

This is mechanically enforced, not merely documented:

- `ScreenFactOrderingTest.B01` walks `src/main/java/com/interstellar/proxy` and fails if
  any file that mentions `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` / `onScreenChanged` /
  `onUserPresent` also calls `.wake()` or `.pause()`.
- `ScreenFactOrderingTest.B02` fails if `PlatformFacts.kt` gains `ResetNetwork`,
  `restartService`, `reload`, `closeService`, `Thread.sleep` or `delay(`.
- `InterfaceResolutionEpochTest.N10` asserts at **bytecode** level that
  `DefaultNetworkMonitor` contains no `java/lang/Thread`.

Do not "restore" these primitives by wiring them up.

### 3. An observation epoch, not a transition counter. (`7275c0f`, `481e370`, `15c432d`)

`ScreenFactState.onChanged` bumps `observationEpoch` on **every** observation, including
a same-value one. The startup screen seed captures the epoch **before** the platform read
and applies its value only if the epoch is unchanged. A same-value broadcast is a newer
observation and must invalidate a snapshot.

This is a **latent** contract: the receiver has no `Handler`, so the overwrite is not
reachable on today's call path. It becomes live the moment a `Handler` is added or
`install()` moves off the main thread — which is why it is guarded. Do not optimise the
epoch into a value-change counter.

### 4. Record facts before effects; listeners run outside the fact lock. (`36dee73`, `56eb255`, `15b5ff1`)

`PlatformFacts.lock` guards the fact fields, the session identity and enqueue ordering,
and is never held across a listener or a native call. `onForegroundChanged` is
brace-bodied so `dispatchForegroundListeners` runs after the `synchronized` block.

Foreground delivery is ordered by a ticket plus a watermark (`foregroundSequence` /
`foregroundDelivered`, `-1` = nothing delivered). Only outermost notifications take a
ticket; nested ones deliver inline; the watermark advances with `maxOf`.

### 5. Session and generation ownership. (`51cb7f5`, `1a86247`, `24c8372`, `bdb4ced`, `74ff3ce`, `7d4218a`)

Three distinct ownership axes — do not conflate them:

| Axis | Owner | Guard |
|---|---|---|
| fact delivery to a core | `PlatformFacts.Session.id` | `detachAndDrain` unbinds only a matching id |
| notification and its traffic line | `ServiceNotification.generation` | `NotificationPublishGate` |
| native interface listener | the listener object itself (`===`) | `InterfaceListenerRegistry` |

`BoxService`'s `CoreLifecycle` is the publish authority:
`ownsServiceLocked = !closed && attempt == startAttempts && attempt > stopStaleUpTo`.
A destroyed service instance is terminal.

The **UI command-client** verdict (`UiClientLifetime`) is a *different* axis: it governs
one socket in the app process and cannot revive the core, the TUN or the notification. An
earlier revision of this file merged the two; keep them separate.

### 6. TUN ownership.

Only `VpnService.Builder.establish()` yields the fd, and `VPNService` owns it.
`ProxyService` is headless and cannot open a TUN (`PlatformInterfaceWrapper.openTun`
defaults to `error("invalid argument")`). `onRevoke` must not block the main thread —
doing so self-deadlocked before `cf3dc09`.

### 7. Binary provenance, not metadata. (`89951ae`, `f133dc9`, `25ac148`)

Version text is not engine proof. Revision identity, AAR self-consistency, per-ABI engine
presence, artifact identity and signing are five different claims with five different
proofs. An APK whose *own* ABI engine is absent is a real shipped defect that `25ac148`
closed by adding a **gate** — not by adding an AAR.

## Current conventions (not invariants)

| Convention | Why it is not hard |
|---|---|
| Compose for UI | a presentation choice; `navigation-compose` and `datastore` are declared dependencies that no source imports |
| per-ABI splits, two 64-bit ABIs | a build decision; the AAR still packages four engines. Adding an ABI means changing the Gradle split list, the gate defaults, the final-APK guard and the release evidence **together** |
| `testing` as the default **core branch** for dev CI | a workflow default; dev CI is manual-dispatch only since `f2cde01`, so `testing` never runs by itself |
| `settings.properties` + JSON files for persistence | an implementation detail; no Room/DataStore is used |
| hand-rolled `NavState` | an implementation detail; the navigation library is a declared-but-unused dependency |

## What this file deliberately does not claim

- It does not claim any of the above is verified at runtime. `NEEDS_DEVICE` items are in
  `ACCEPTANCE.md`.
- It does not claim the invariants hold on the core's side. The core is a separate
  repository; see `BASELINE.md` and `modules/core-integration.md`.
