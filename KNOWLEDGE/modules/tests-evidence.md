# Tests, gates and what they actually prove

## The one command that runs the whole Android test suite

```powershell
$env:JAVA_HOME       = 'C:\Deepseek\安卓客户端\.tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME    = 'C:\Deepseek\jiejiebox-work\android-sdk'   # junction -> 安卓客户端\.android-sdk
$env:ANDROID_SDK_ROOT= $env:ANDROID_HOME
$env:GRADLE_USER_HOME= 'C:\Deepseek\jiejiebox-work\.gradle-home-guard'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Set-Location 'C:\Deepseek\jiejiebox-work\satelite-one'
.\gradlew.bat --offline :app:testDebugUnitTest --console=plain
```

Everything needed is cached locally (JDK 17.0.20.1+1, Android SDK with `android-36`,
Gradle 9.7.0 distribution, dependency cache), so `--offline` works. Read results from
`app/build/test-results/testDebugUnitTest/*.xml`.

### Result at the Stage-2 review (2026-10-12, production baseline `2cb6eb9`)

```text
suites = 23   tests = 254   failures = 0   errors = 0   skipped = 0   exit = 0
```

This upgrades every "policy/ordering/guard" claim below from *historically verified* to
`VERIFIED_UNIT` at the current baseline. Re-run it before quoting these numbers.

## What the suite is made of

All tests are **pure JVM** — no emulator, no Robolectric, no instrumentation source set
(`testOptions.unitTests.isReturnDefaultValues = true`). That shapes what they can prove.

| Class | Tests | Proves |
|---|---|---|
| `PlatformFactsTest` | 20 | raw trim levels forwarded unmodified and in order; fact routing |
| `CoreLifecycleTest` | 18 | attempt/watermark/teardown state machine |
| `LogRingBufferTest` | 18 | ring saturation without losing the tail |
| `ScreenFactOrderingTest` | 15 | level-vs-edge policy, seed order, no timer/poll in the file |
| `ScreenSeedWiringTest` | 15 | epoch-before-read wiring; same-value invalidation |
| `AppViewModelWiringTest` | 14 | every establish sits inside a `UiClientLifetime` verdict (source scan) |
| `StopConvergenceTest` | 13 | 200 interleavings of stop vs a newer start |
| `InterfaceListenerRegistryTest` | 11 | identity-keyed removal across a core handover |
| `SessionGateTest` | 11 | a closed page session cannot be revived by a late callback |
| `ActivityForegroundTrackerTest` | 10 | balanced Activity counting; config-change absorption |
| `MinimalConfigBuilderTest` | 10 | generated config shape, including the **absence** of an external controller |
| `SubscriptionFetcherTest` | 10 | subscription header parsing |
| `NotificationPublishGateTest` | 9 | publish/terminate order; late traffic from a dead generation refused |
| `RestartOwnershipTest` | 9 | 200 interleavings of start vs an in-flight teardown |
| `InterfaceResolutionEpochTest` | 8 | losing resolve loops cannot publish; **bytecode scan** that `DefaultNetworkMonitor` has no `java/lang/Thread` |
| `PlatformFactsListenerLockBoundaryTest` | 8 | no listener call under the fact lock |
| `LogUiPublishGateTest` | 8 | 75 ms coalescing without losing the latest version |
| `TrafficDisplayTest` | 7 | unavailable data is not displayed as zero |
| `RawConfigDetectorTest` | 6 | raw config format sniffing |
| `UrlTestTargetTest` | 5 | URL-test target selection |
| `TeardownFailureTest` | 4 | a failed teardown still converges |
| `AppUpdateCheckerTest` | 3 | release-tag comparison |

## Three test styles — know which one you are reading

1. **Pure model tests** (the majority): drive a class directly. They prove the rule, not
   the platform.
2. **Source-scanning guard tests** (`ScreenFactOrderingTest`,
   `AppViewModelWiringTest`, parts of `ScreenFactOrderingTest.E02`): read
   `src/main/java/...` as text and assert on it — e.g. "exactly one
   `reportDeviceWake` enqueue site", "no `delay(`/`Thread.sleep` in this file".
   These fail on refactors that are behaviourally harmless. That is intended: they guard
   invariants the type system cannot express.
3. **Bytecode-scanning guard tests** (`InterfaceResolutionEpochTest.N10`): read compiled
   class bytes.

Styles 2 and 3 use working-directory-relative paths, so they pass only when Gradle runs
them from `app/`. Running them another way gives false failures.

## What the tests do **not** prove

- No emulator or device evidence is produced by this suite.
- No test exercises a real `libbox` command socket, a real TUN, or a real
  `VpnService`. `libbox.aar` is a compile-time dependency here, not a test subject.
- No test proves OEM scheduling, Doze, real unlock delivery, real memory-trim pressure,
  real cellular↔Wi-Fi transition, or real `onRevoke`.
- `:app:verifyCoreProvenance` is a **Gradle gate**, not a unit test. It proves an AAR is
  self-consistent with its own producer record and that the named bytes contain the
  advertised identity. It is not run by `testDebugUnitTest`; it is wired into
  `assembleDebug`/`assembleRelease`.

## The verification chain that guards release

Five links, each proving something the others cannot. Do not let one stand in for
another.

| Link | Proves | Does **not** prove |
|---|---|---|
| `check_core_provenance.py` (release CI only) | the requested `version.properties` SHA equals both producer fields; every shipping ABI has `jni/<abi>/libbox.so`; a packaged `libbox.so` contains that SHA | that an APK copied from another job is the verified output |
| `:app:verifyCoreProvenance` (Gradle) | an AAR used by Gradle is self-consistent with its own record and its binary carries the advertised identity | that development CI used the release pin |
| release ABI/engine guard | each final split contains the engine digest for **its own** ABI | signing or GitHub publication state |
| SHA256 / build-info / `verify_release_bundle.py` | the named final files are the files checksummed, described, uploaded and eligible to publish | that a signer is trusted by a device |
| `apksigner verify --print-certs` | **observed** signature state of the produced APK | any claim based only on a filename or Gradle variant |

The single-ABI-AAR failure mode — an arm64 APK shipped with no engine while another ABI's
engine was present — is retained as a negative test.

Negative suites run on **every** dev and release CI run, so the gates are seen to fail:
`test_check_core_provenance.py`, `test_release_abi_guard.py`,
`test_release_publish_gate.py`.

## How to re-check each claim

| Claim | Command |
|---|---|
| unit-level policy, ordering, guards | the Gradle command at the top of this file |
| the AAR matches the pin | `python .github/scripts/check_core_provenance.py app/libs/libbox.aar app/libs/libbox.provenance 1965bbf351710b86722838ae61a91b62c97d29de` |
| the gates still fail closed | `python .github/scripts/test_check_core_provenance.py`, `…/test_release_abi_guard.py`, `…/test_release_publish_gate.py` |
| dev CI vs release CI pin behaviour | read `.github/workflows/android-ci.yml` (resolves `testing` HEAD unless dispatched with `core_ref`) against `release-apk.yml` (resolves `version.properties:coreCommit` and proves it fetchable) |
| signing state | `measure_apk_signature.sh <unsigned\|signed> <apk-dir>` |

## Development CI is not release evidence

`android-ci.yml` is **manual-dispatch only** — the push and pull-request triggers were
removed to save Actions minutes. It resolves `testing` HEAD unless explicitly given a
40-hex `core_ref`, and `testing` moves. A green development run therefore says nothing
about the release pin. Only release CI resolves and fetch-validates the pin, and only
the tag/non-dry-run `signed-by-user` path may publish.

## Evidence status

`VERIFIED_UNIT` for the 254/0 result above (produced at this review).
`VERIFIED_SOURCE` for the suite inventory, the gate chain and the CI distinction.
`NEEDS_DEVICE` for everything in "what the tests do not prove".
