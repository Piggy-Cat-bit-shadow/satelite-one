# Architecture

```text
Compose UI / ViewModels            (ui/, ktx/)
  |  command client(s), configuration, DTOs
  +-- utils/CommandClient ---------> libbox command socket (one server, many clients)
  |       ^ owned by AppViewModel under a UiClientLifetime verdict;
  |         plus a page-scoped connections client, a log client,
  |         a notification status client, and short-lived standalone clients
  |
BoxService  (plain class, one per Service object)   bg/BoxService.kt
  |  CoreLifecycle: the publish authority
  +-- VPNService   (TUN)          openTun() -> establish() -> fd
  `-- ProxyService (headless)     no TUN, never calls openTun
        |
     core/SingBoxCore
        |  one libbox CommandServer + one Libbox.newPlatformEvents(server)
        v
     app/libs/libbox.aar  ->  pinned sing-box @ 1965bbf
```

## The two directions at the libbox boundary

| Direction | Type | What crosses |
|---|---|---|
| app → core | `bg/PlatformFacts.kt` → `PlatformFactSink` → `PlatformEvents` | screen level, foreground level, raw trim integer, one-shot `reportDeviceWake()` |
| core → app | `bg/PlatformInterfaceWrapper.kt` (libbox `PlatformInterface`) | `openTun`, `findConnectionOwner`, `getInterfaces`, `readWIFIState`, `localDNSTransport`, default-interface monitor |

They are opposite directions, not two implementations of one idea, and neither wraps the
other. Confusing them is the most likely first mistake; see
`modules/platform-facts.md` and `modules/vpn-network.md`.

## Ownership at a glance

| Concern | Owner | Not the owner |
|---|---|---|
| core lifecycle, TUN fd reference, notification, `SERVICE_CLOSE` receiver | `BoxService` | `VPNService` |
| TUN establishment (`builder.establish()`) | `VPNService` | `BoxService` |
| platform fact collection and forwarding | `PlatformFacts` | any UI code, `ServiceNotification` |
| command socket clients | deliberately plural — see below | a single "the" client |
| physical-network tracking and interface resolution | `DefaultNetworkMonitor` | `PlatformFacts` |
| app-wide UI state and start/stop orchestration | `AppViewModel` | pages |
| per-page live sessions | `ConnectionsViewModel`, `LogsViewModel` | `AppViewModel` |

## One command **server**, deliberately plural clients

A recurring misreading: "there is one command socket" is true of the core, not of the
app. Four long-lived clients exist — the main UI status/control client owned by
`AppViewModel` (lifetime = `UiClientLifetime` verdict), a page-scoped connections client,
a log client, and a notification status client bound to its own scope — plus short-lived
standalone clients created per one-shot call at ten call sites via
`CommandTarget.standaloneClient()`.

**Do not merge them.** Page-scoping is a deliberate retention fix (`060b009`), and the
notification's own client is what lets it publish without a UI. The single invariant is
that the *main UI* client's existence must be decided by `UiClientLifetime`, never by page
visibility or a callback.

## Not present, by design

- **No external controller.** `external_controller`, `clash_api`, `secret` and
  `external_ui` appear in no emitted config; `MinimalConfigBuilderTest` asserts their
  absence. Control is the libbox local command socket only.
- **No engine selector.** `CoreEngines.create()` returns `SingBoxCore` unconditionally.
- **No app-owned user-configurable routing or DNS policy.** The generated config is a
  fixed minimal set; the only escape hatch is byte-for-byte raw sing-box pass-through.
- **No universal APK and no 32-bit ABI shipped** — though the AAR still packages four
  engines.
- **No telemetry.** The only non-core network callers are the GitHub release check and
  the user's own subscription URLs.
- **No `pause`/`wake` wiring.** `ProxyCore.pause()`/`wake()` exist and are never called;
  see `INVARIANTS.md` §2 for the tests that enforce it.

## AIDL and the quick tile

There is a third control surface besides the UI and the core: an AIDL binder
(`IService`/`IServiceCallback`) served via `VPNService.onBind`, plus `QuickTileService`.
Its payloads are **enum ordinals** (`Status`, `Alert`) — positional wire values. They are
safe only because both ends are the same build in one process; inserting a constant
renumbers the contract.
