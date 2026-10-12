# Project

Jiejiebox (`satelite-one`) is an Android 7.0+ Kotlin/Compose client for exactly one
in-process custom sing-box `libbox.aar`. The app owns Android integration,
import/configuration and presentation. The core owns protocol semantics, runtime/TUN
policy, the command server, traffic/status and libbox API behaviour.

## The one rule that outranks the rest

> Do not create a second Android lifecycle or power-policy state machine.

The app forwards facts; the core decides policy. Every "obvious improvement" that maps a
screen state to a wake, or a foreground change to a pause, is a second policy owner and is
wrong here. `INVARIANTS.md` §2 lists the source-scanning tests that enforce this.

## The product boundary

The old multi-core/sidecar design (mihomo, Xray, a JNI bridge, geodata assets, a
"use raw config" switch with built-in rule injection) was removed by `d2a2e98`,
`d628ef0`, `ebd2f79` and `aec3893`. Do not recreate a selector, a sidecar, or app-owned
routing/DNS/power policy from old history.

Raw sing-box configuration passes through **byte-for-byte**, with no hidden routing or DNS
injection (`aec3893`, `ebd2f79`). Note the scope precisely: only a **sing-box** body can be
handed to this core verbatim. Clash and Xray bodies are detected and retained but cannot
be applied — see `modules/import-subscription.md`.

## Residue that must not be read as capability

The claim above is true of the code, not of `grep`. The following still mention the
removed design and are **not** features:

| Where | What |
|---|---|
| `.gitignore` | sidecar-core paths and a `tools/fetch_cores.py` that no longer exists |
| `data/subscription/RawConfigFormat.kt` | `CLASH`, `SINGBOX`, `XRAY` — detection only; Xray is never applied |
| `ui/AppViewModel.kt`, `ui/pages/NodesPage.kt` | comments saying "sidecar" / "mihomo" |
| `.github/workflows/android-ci.yml` | an example dispatch at a superseded pin |

## Unreleased state

`git describe HEAD` = `v0.5.10-88-g942b6fe`. The last released product (`v0.5.10`) is the
**three-core** app; every single-core commit is unreleased, and `versionName` has not
moved since. `release-apk.yml` requires the tag to equal `versionName`, so tagging
requires bumping `version.properties` first.

## What this repository is not

- It is not a fork of anything: one remote, one linear branch, 199 commits, no merge
  commits, no submodules, no upstream.
- It does not vendor its engine: `app/libs/libbox.aar`, `app/libs/libbox.provenance` and
  `app/src/main/jniLibs/` are gitignored. The engine is a local artifact whose identity is
  asserted by a pin plus a byte search — see `modules/build-release.md`.
- It is not CI-verified at HEAD. See `ACCEPTANCE.md`.
