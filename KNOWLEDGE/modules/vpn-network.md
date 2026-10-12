# VPN, default-interface monitoring and DNS

Service ownership is in `lifecycle-services.md`; this file owns TUN establishment,
interface monitoring and resolution.

## TUN establishment

`VPNService` owns Android VPN establishment and passes the fd to libbox. Route, DNS and
per-app options are applied only when the generated config says `auto_route = true`:

| Builder action | Gate | Current effective state |
|---|---|---|
| `addAddress` / `addRoute` / `addDnsServer` | `options.autoRoute` | on (generated config sets `auto_route=true`) |
| per-app include / exclude | `options.includePackage` / `excludePackage` | on when the user selects apps |
| `addDisallowedApplication` for the app itself | always | on |
| `setMetered(false)` | API 29+ | on |
| `allowBypass()` | `Settings.allowBypass` | **never** — the setting has no writer |
| `setHttpProxy` | `isHTTPProxyEnabled && API 29+ && Settings.systemProxyEnabled` | **never** — the setting has no writer |

The two "never" rows are explained in `lifecycle-services.md`; do not add a writer
without a product decision.

`onRevoke` calls `stopService()`. The comment records that the previous
`runBlocking { withContext(Main) }` self-deadlocked on the main thread. Do not
reintroduce a blocking main-thread hop there.

## Default-interface monitoring

`DefaultNetworkMonitor` is an `object` — one process-wide monitor — with three
separately owned pieces of state:

| State | Purpose |
|---|---|
| `defaultNetwork` | the physical `Network` the resolver binds to |
| `listeners: InterfaceListenerRegistry<InterfaceUpdateListener>` | identity-keyed (`===`) registry, one entry per core |
| `resolution: InterfaceResolutionEpoch` | decides which resolve attempt may publish |

`start(owner)` / `stop(owner)` are **per owner**, and VPN-capable networks are ignored
so the tunnel cannot become its own default interface.

`checkDefaultInterfaceUpdate` claims an epoch for **every** event including a loss,
reports the loss immediately as `("", -1, false, false)`, and otherwise launches the
resolve loop, which re-checks the epoch before and after the lookup and publishes at
most once. Publishing from a losing loop, or announcing a loss without claiming an
epoch, is the class of defect these two types exist to prevent.

`DefaultNetworkListener` owns a single process-wide actor holding the owner→listener
map, with per-SDK registration paths and `runBlocking { send }` callbacks on the
connectivity thread. Resolution runs on `resolveDispatcher` (default `Dispatchers.IO`,
never Main) — `InterfaceResolutionEpochTest` asserts by bytecode scan that
`DefaultNetworkMonitor` contains no `java/lang/Thread`.

### Why the registry is identity-keyed, not slot-keyed

A headless `ProxyService` and a `VPNService` can hand over to each other. If the
registry were a single mutable slot, the outgoing core's close would clear the
incoming core's listener, and the UI would report "connected" with no network.
`remove` therefore removes only the caller's own identity. A refactor to
`listener = null` reintroduces exactly that fault.

## Resolver

`LocalResolver` implements libbox's `LocalDNSTransport` over Android's `DnsResolver`,
bound to `DefaultNetworkMonitor.defaultNetwork` on every query, with cancellation wired
to the core's context. Two behaviours matter:

- It hard-errors with `error("missing default interface")` when no network has been
  published yet — that exception surfaces **inside the core**.
- `DefaultNetworkMonitor.stop(owner)` does **not** clear `defaultNetwork`, so a
  `Network` captured before a stop can still be handed to `DnsResolver` afterwards.

Neither is a proven bug; both are unguarded edges worth knowing before changing
monitor lifetime. `VERIFIED_SOURCE` for the code shape, `UNKNOWN` for whether either
path is reachable on a real device.

## AI caution: `command.sock` is not a liveness oracle

`SubscriptionFetcher` and `AppUpdateChecker` decide "the core is running, so route
through the local mixed inbound" by testing
`File(filesDir, "command.sock").exists()`.

The path is right — the core creates the socket at
`filepath.Join(sBasePath, "command.sock")` and `InterstellarApplication` sets
`basePath = filesDir` (checked against the pinned core `1965bbf`, not inferred).

But the file is **not** a liveness signal:

- `CommandServer.Start()` calls `os.Remove(sockPath)` before listening, so a stale file
  from a previous run is harmless to the core.
- `CommandServer.Close()` stops the gRPC server and closes the listener but does **not**
  remove `sockPath`.

So after a clean stop the file remains and the probe still answers "core is running".
The practical effect is a subscription fetch that may attempt `127.0.0.1:2080` while
the core is down and fall back to a direct connection after a failure — a latency
cost, not a correctness failure. Do not delete the probe, and do not treat it as
evidence of core state.

## Not network policy

This subsystem is connectivity integration only. It is **not** a wake policy, not a
route policy and not a DNS policy: the pinned core owns those. Android forwards raw
facts and interface results, and `DefaultNetworkMonitor` is deliberately not a wake
substitute.

## Evidence

`VERIFIED_SOURCE` for the monitor, resolver, registry and TUN option tables, re-read at
`942b6fe`. The `command.sock` behaviour is `VERIFIED_SOURCE` against the pinned core
revision `1965bbf`. `VERIFIED_UNIT`: `InterfaceResolutionEpochTest`,
`InterfaceListenerRegistryTest`, `UrlTestTargetTest` — executed and passing 2026-10-12,
see `tests-evidence.md`.

`NEEDS_DEVICE`: real cellular↔Wi-Fi transitions, airplane mode, OEM process killing,
actual `onRevoke`, and Doze-driven interface loss.
