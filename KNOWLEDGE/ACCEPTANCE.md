# Acceptance and evidence

| Claim | Evidence | Baseline status |
|---|---|---|
| Lifecycle/order policy | JVM tests under `app/src/test` | VERIFIED |
| AAR pin/provenance gate rejects bad identity or a missing shipping-ABI engine | Python negative tests and Gradle gate | VERIFIED historically; not rerun for docs refresh |
| Final release guard rejects a split whose own ABI engine is absent/mismatched | release-script tests, including `25ac148` negative case | VERIFIED historically; not rerun |
| Runtime behaviour | Round 8 reports | emulator-only; NEEDS_REAL_DEVICE |
| OEM Doze/cellular/onRevoke/unlock | no broad evidence | UNVERIFIED / NEEDS_REAL_DEVICE |

Round 8 uses a prior fixed core and Android 16 x86_64 emulator; it is neither current-release nor real-device proof. Development CI may test testing or an explicit SHA; only release CI resolves/fetch-validates `version.properties:coreCommit`.

A releasable artifact requires a fresh pinned release run, producer/binary provenance
validation, per-ABI AAR and final-APK engine verification, build-info/checksums, and
apksigner measurement. Only tag/non-dry-run `signed-by-user` output may publish;
unsigned/default release and debug-signed output are rejected. Historical successful
tests prove the gates' behavior at their recorded commits, not a fresh artifact for
the current pin.
