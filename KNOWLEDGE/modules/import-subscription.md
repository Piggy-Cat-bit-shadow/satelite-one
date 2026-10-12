# Import, subscription and the configuration boundary

## Where the app stops and the core starts

The app **parses, stores and presents**; it does not route. A subscription produces either

- a **generated minimal config** (`data/config/MinimalConfigBuilder.kt`), or
- a **byte-for-byte raw sing-box body** passed through unchanged.

Both are validated with `Libbox.checkConfig` and written to
`filesDir/active.json` via `ConfigStore`. Nothing else in the app touches routing.

## AI caution: only a *sing-box* raw body is applicable

This is the most under-documented behaviour in the import path:

```kotlin
// SubscriptionRepository.applyRawConfigIfMatching
val format = RawConfigFormat.from(sub.configFormat)
// Only a sing-box body can be handed to this core verbatim; Clash/Xray
// bodies were only ever pass-through for the sidecars this client no
// longer ships.
if (format != RawConfigFormat.SINGBOX) return null
```

`RawConfigFormat` still enumerates `CLASH`, `SINGBOX` and `XRAY`, and the detector still
identifies all three — but a Clash or Xray body is only **detected and stored**. It is
never applied; `regenerateActiveConfig` falls back to the generated minimal config.

The user-visible consequence: importing a Clash config as "raw" silently yields a
*generated* config with this app's selector/urltest layout, not the config they supplied.
Nothing in the UI explains that. This is a product gap, not a bug to fix by feeding an
Xray body to libbox — the core cannot run it.

Do not delete the detector: it is what decides whether the raw path is allowed at all.

## The generated config is a fixed minimal policy

`MinimalConfigBuilder` emits: sniff, `hijack-dns`, `ip_is_private → direct`,
`final = selector`, a `dns-local` transport for outbound dials plus a DoH server through
the selector, `prefer_ipv4`, LAN `route_exclude_address`, tun `172.19.0.1/30`,
`auto_route=true`. No `stack`, no `mtu`, no rulesets, no ad-block, no geo or CN split.

`MinimalConfigBuilderTest` asserts the **absence** of an external controller. There is no
`clash_api` block — not even an empty one — and that absence is deliberate: `919089d`
added an empty one claiming the traffic manager needed it, and `fb5a361` reverted it
after establishing that the premise was false.

Do not add `external_controller`, `clash_api`, `secret` or `external_ui` to "enable"
traffic statistics. Traffic is core-sourced through the command socket.

## Subscription metadata

`SubscriptionFetcher` uses OkHttp with a Clash-Verge user agent and parses
`subscription-userinfo` and `Content-Disposition`. `AppUpdateChecker` compares release
tags via a GitHub `releases/latest` 302 `Location`.

Both prefer to ride the local mixed inbound at `127.0.0.1:2080` when
`filesDir/command.sock` exists. That probe is a file-existence check, not a liveness
check — see the caution in `modules/vpn-network.md`.

`UpdateWorker` refreshes URL subscriptions periodically and calls `serviceReload()` when
the quick tile is active.

## Unavailable data is never zero

Traffic and status are core data, and "unknown" is a distinct state from "0 B/s":
`TrafficDisplay` models `Idle` / `Unavailable` / `Live` and `MinimalConfigBuilder`'s
absence of an external controller is what keeps it core-sourced. Subscription metadata is
*app* data and may legitimately be absent; the UI must distinguish unavailable from a
successful zero. `TrafficDisplayTest` pins the display rule.

## What is not persisted

`Settings` is a `java.util.Properties` file (`filesDir/settings.properties`) with every
setter rewriting the whole file; `ConfigStore` is a single JSON file. There is no Room and
no DataStore, despite both being declared dependencies.

## Evidence

`VERIFIED_SOURCE` for the raw-config gate, the generated config shape and the metadata
parsing, re-read at `942b6fe`. `VERIFIED_UNIT`: `MinimalConfigBuilderTest` (10),
`RawConfigDetectorTest` (6), `SubscriptionFetcherTest` (10), `AppUpdateCheckerTest` (3),
`TrafficDisplayTest` (7) — executed and passing on 2026-10-12.

`NEEDS_DEVICE` / `UNVERIFIED`: real subscription endpoints, real proxy availability, and
what a user actually sees after importing a Clash body as a raw config.
