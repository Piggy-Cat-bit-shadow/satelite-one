# Acceptance and evidence

Every row names the artifact and revision that produced it. A claim without a named
artifact is not evidence.

## Current status

| # | Claim | Evidence | Environment | Status |
|---|---|---|---|---|
| 1 | Lifecycle, ordering, guard and provenance policy holds | 23 JVM suites, 254 tests, 0 failures, 0 skipped — re-run at production baseline `2cb6eb9` during this review | JVM, `--offline`, JDK 17 | **`VERIFIED_UNIT`** — see `modules/tests-evidence.md` for the command |
| 2 | The AAR matches the release pin | `check_core_provenance.py app/libs/libbox.aar app/libs/libbox.provenance 1965bbf…` → exit 0; the old pin `c35faabf…` → exit 1 | local, real 118,160,367-byte AAR | **`VERIFIED_STATIC`** |
| 3 | The release gate rejects a split whose **own** ABI engine is missing or mismatched | `test_release_abi_guard.py`, `test_release_publish_gate.py`, `test_check_core_provenance.py`, and `verify_release_bundle.py` against the real local APKs + real AAR (exit 0; arm64 engine stripped → exit 1) | local | **`VERIFIED_STATIC`** |
| 4 | Development CI runs the suite | — | — | **`VERIFIED_CI` only at `ae89388`** (run #54). **No CI run exists on any commit after it**, and HEAD is 13 commits past. Do not describe row 1 as CI-verified |
| 5 | The release workflow's current gate set executes | — | — | **`NEVER RUN`** — `25ac148` added the ABI/engine guard and `_zipcontent.py` *after* the newest release-workflow run (#10). No recorded run contains the new step |
| 6 | The pinned core is rebuildable from source | — | — | **`UNVERIFIED`** — see the conflict below |
| 7 | Emulator regression (toggles, stop→start, no crash/ANR) | `docs/ROUND8-REPORT.md` | `emulator-5554`, Android 16 / API 36, **x86_64** | **`VERIFIED_EMULATOR`** — for product `ae89388` / core `c35faabf`, which predates the baseline |
| 8 | Real-device behaviour | — | — | **`NEEDS_DEVICE`, never attempted.** No `adb devices` model string, no `ro.product.model`, no device name appears anywhere in the repository |
| 9 | The **shipping** ABI has been executed anywhere | — | — | **`NEVER`** — all evidence is the `x86_64` debug split. `arm64-v8a` is the shipping target and has no execution evidence on any device or emulator |
| 10 | A signed release artifact exists | — | — | **`NOT_RUN`** — 15 tags, **0 releases**; every release-workflow run was a dry run and skipped the signing and publish steps |
| 11 | OEM Doze, real unlock, real `onRevoke`, cellular transitions | — | — | **`BLOCKED`** for three *different* mechanical reasons, not one: no device; the AVD's lock screen is disabled (`locksettings get-disabled=true`) and `ACTION_USER_PRESENT` is a protected broadcast `am broadcast` cannot send; the AVD charges, so forced Doze never engages; no shell-reachable `onRevoke` path |
| 12 | `Settings.accentId` defaults to `"green"` but the accent presets are mint/sky/taro/peach/milk; `Accents.selectedId` normalises on read | — | **`OPEN`** — a default that is remapped at runtime, visible only if both files are read |

## Two conflicting records you must resolve before a release

**1. Is the pinned core rebuildable from source at `1965bbf`?**

- `docs/android-screen-power-parity-final-report.md` states the candidate CI run's
  `Cache libbox.aar` was **"Cache not found"** → the core was genuinely built from source.
- The round-8 report's own text calls its pinned run a **cache hit**, i.e. the AAR was
  restored, not rebuilt.

Both cannot describe the same run. The parity report itself carries a "缓存表述更正（第八轮）"
correction, so the intended reading is that the *earlier* run was a cache hit and the
candidate run rebuilt — but the two documents still read as contradictory.

**Why it matters:** a cache hit means the AAR was never compiled at that pin, so
"the pin builds" is unproven. A tag push today would have to build `1965bbf` from source
with **no cache for that key**, on a path that failed on every push in this repository's
history (`cp: cannot stat 'libbox.aar'`). Verify by deleting the `libbox-…-1965bbf…`
cache and re-dispatching before tagging.

**2. The local AAR's byte count.** `docs/android-screen-power-parity-final-report.md`
records `libbox.aar (29217593 bytes)` for the pin check. The AAR in this checkout is
**118,160,367 bytes** (sha256 `c7eac580…`). One of the two describes a different artifact.
The AAR present here does carry the current pin exactly once in each of its four
`jni/<abi>/libbox.so` entries, so the gate's *conclusion* reproduces; its *recorded
input* does not.

## What a releasable artifact still requires

A fresh pinned release run; producer/binary provenance validation; per-ABI AAR **and**
final-APK engine verification; build-info and checksums; `apksigner` measurement. Only
tag / non-dry-run / `signed-by-user` output may publish; unsigned and debug-signed output
are rejected.

Historical successful tests prove the gates' behaviour **at their recorded commits**, not
that a fresh artifact exists for the current pin. Upload success would not establish that
a device can install it.

## Follow-up (production code — not fixed by this knowledge refresh)

| Item | Why it matters |
|---|---|
| `BoxService` broadcasts `SERVICE_STOPPED` **before** `releaseCore()`, so the UI can show `Stopped` while the TUN is still up for the duration of the release | an accurate "stopped" signal is what the notification path is gated for; the broadcast path is not |
| `Settings.dynamicNotification` has no writer, so the dynamic-traffic notification cannot be turned off from the UI | a user-visible setting that does nothing |
| `LocalResolver` hard-errors when no default interface has been published, and `DefaultNetworkMonitor.stop()` does not clear it | DNS queries can throw into the core or use a stale network |
| `command.sock`'s existence is used as a liveness probe, but the core does not remove it on `Close()` | a wasted connect attempt; see `modules/vpn-network.md` |
| `.github/scripts/test_release_abi_guard.py` cases N5 and N5b build byte-identical fixtures, so N5b is a duplicate rather than the "forged metadata" case its comment claims | a negative test that does not test what it says |
| `LICENSE` is MIT with a single copyright, while `bg/DefaultNetworkListener.kt` carries two third-party copyright headers and states it was ported from `sing-box-for-android` (**GPLv3**) | a redistribution question; no commit reconciles it |
