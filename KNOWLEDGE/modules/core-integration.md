# Core integration

## Ownership boundary

The app hosts SingBoxCore/libbox CommandServer, supplies Android interfaces, packages the
AAR and presents data. The pinned core owns protocol, TUN/runtime policy, command
operations, traffic control and libbox API semantics. Do not recreate core policy in
Kotlin.

## The app ↔ core surface, verified against the pinned core

| Surface | App side | Core side | Contract |
|---|---|---|---|
| `PlatformInterface` (core → app) | `bg/PlatformInterfaceWrapper.kt` | `experimental/libbox` `PlatformInterface` | `openTun`, `findConnectionOwner`, interfaces, WIFI state, default-interface monitor |
| `PlatformEvents` (app → core) | `core/SingBoxCore.kt` via `Libbox.newPlatformEvents(server)` | `platform_events.go` | exactly `MemoryTrim(int32)`, `SetScreenOn(bool)`, `SetAppForeground(bool)`, `ReportDeviceWake()`, `Close()` — one bridge per `CommandServer` |
| Command socket | `utils/CommandClient.kt` | `command_server.go` `Start()`: `filepath.Join(sBasePath, "command.sock")` | the socket lives in `basePath`, which the app sets to `filesDir` |

These three were read from the core at `1965bbf`, not inferred from the Kotlin wrappers.
If a future core bump renames a `PlatformEvents` method, `core/SingBoxCore.kt` is the
single place that breaks.

The app does **not** configure an external controller: no `external_controller`,
`clash_api`, `secret` or `external_ui` appears in any emitted config, and
`MinimalConfigBuilderTest` asserts their absence. Control is the local command socket
only.

## One command server, deliberately plural clients

The core has one command socket. The app has several clients on purpose: the main UI
status/control client (`AppViewModel`, lifetime = `UiClientLifetime` verdict), a
page-scoped connections client, a log client, a notification status client bound to its
own scope, and short-lived standalone clients for one-shot calls.

`CommandClient` is not a core-liveness oracle, and page scoping is a deliberate retention
fix (`060b009`). **Do not merge the clients onto the main one.** See `ARCHITECTURE.md`
and `modules/ui-data.md`.

## Facts are facts only

The app forwards raw Android facts; it does not interpret them. In particular the AAR's
own API surface exposes no distinct **resume edge**, so `CommandServer.wake()` means
un-pause rather than "the user unlocked the device". Mapping `SCREEN_ON` or
`USER_PRESENT` onto it would wake a locked device — the exact substitution the platform
rule forbids.

```text
BLOCKED_BY_CORE_ABI: a semantic resume edge needs an explicit core API.
Do not synthesise one in Kotlin.
```

`INVARIANTS.md` §2 lists the source-scanning tests that enforce this.

## Traffic and status are core-sourced

`919089d` added an empty `clash_api` block on the theory that the traffic manager needed
it; `fb5a361` **reverted** that half after establishing the premise was false — libbox
always supplies a platform log writer — and that the real cause was a measurement error
(`adb shell` traffic bypasses the tunnel). Unavailable data is displayed as unavailable,
never as zero.

## Evidence

`VERIFIED_SOURCE` for the ownership boundary and the client inventory; `VERIFIED_SOURCE`
against the core revision for the three surfaces above. Wrapper and lifecycle tests prove
app policy paths, not core behaviour.

`EXTERNAL_CHECK_REQUIRED` for core-internal semantics (reload, pause/wake level-vs-edge
behavior, command operations): inspect the pinned revision.
