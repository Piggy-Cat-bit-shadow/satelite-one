# Acceptance and evidence

| Claim | Evidence | Baseline status |
|---|---|---|
| Lifecycle/order policy | JVM tests under `app/src/test` | VERIFIED |
| AAR gate rejects bad identity | Python negative tests and Gradle gate | VERIFIED historically; not rerun for docs refresh |
| Release gate rejects unsafe/missing ABI output | release-script tests | VERIFIED historically; not rerun |
| Runtime behaviour | Round 8 reports | emulator-only; NEEDS_REAL_DEVICE |
| OEM Doze/cellular/onRevoke/unlock | no broad evidence | UNVERIFIED / NEEDS_REAL_DEVICE |

Round 8 uses a prior fixed core and Android 16 x86_64 emulator; it is neither current-release nor real-device proof. Development CI may test testing or an explicit SHA; only release CI resolves/fetch-validates `version.properties:coreCommit`.

A releasable artifact requires a fresh pinned release run, per-ABI engine verification, build-info/checksums and apksigner measurement. Only tag/non-dry-run `signed-by-user` output may publish; unsigned/default release and debug-signed output are rejected.
