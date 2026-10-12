# Knowledge Stage 2 — Reinforcement / Independent Review / Compression — Android

Repository `Piggy-Cat-bit-shadow/satelite-one`. This report describes how the Stage-1
knowledge base was re-audited, what was corrected, and where the evidence boundary
actually sits. It is history, not current truth.

---

## A. Baseline

| Field | Value |
|---|---|
| Repository | `Piggy-Cat-bit-shadow/satelite-one` |
| Repo kind | **ANDROID** |
| Branch | `main` (the only branch) |
| Current HEAD | `942b6fe2195918be1f8f4c160cf7caf5af454d09` (2026-10-12) |
| **Production baseline** | `2cb6eb9b8e3721036534ef84b1e4352369ac1484` (2026-10-12) |
| Knowledge revision | `942b6fe` — docs only, **not** a product SHA |
| Release baseline | **none recorded** — 15 tags, 0 releases |
| Latest tag | `v0.5.10` / `6f4124c` — **architecturally superseded** (three-core tree, no `coreCommit`, 88 commits behind) |
| Core pin | `1965bbf351710b86722838ae61a91b62c97d29de`, repo `Piggy-Cat-bit-shadow/sing-box`, branch `testing` |
| Shipping ABIs | `arm64-v8a` (primary), `x86_64` (emulator) |
| Stage-1 knowledge date | 2026-10-12 |
| Stage-2 review date | 2026-10-12 |
| Working tree | clean at review start and end for tracked files |

Upstream / core / design pins: **no upstream remote exists.** One remote, one linear
branch, 199 commits, no merge commits, no submodules, no gitlinks. The root commit
`3b71924` (2026-08-27) is a LICENSE-only shell, so fork-owned history begins there.

### Repository-kind detection

Detected as ANDROID from `app/`, `build.gradle.kts`, `settings.gradle.kts`,
`version.properties` and `app/src/main/java/**`. No Apple markers present.

---

## B. Parallel audit

Eight independent subagents ran, four per repository, plus the orchestrator's own
targeted verification.

| Agent | Scope |
|---|---|
| **A — Source Truth** | recovered the current architecture from `app/src/**`, Gradle, workflows, `version.properties` and `app/libs/**` **without reading `KNOWLEDGE/`**, to avoid anchoring. Read 68 main `.kt` (15,949 lines) and 23 test `.kt` (6,051 lines) |
| **B — Git Evolution** | 199 commits, all 49 SHAs quoted anywhere in the knowledge base validated, tag boundaries, reflog archaeology, message-vs-diff disagreements |
| **C — Verification Truth** | test inventory, CI enumeration via the GitHub REST API (64 runs), gate tracing with fault injection, claims ladder |
| **D — Knowledge Critic** | adversarial audit of every Stage-1 file: SHA validation, invariant re-audit, evidence-vocabulary application, cross-file conflicts, density, dangling references, old-doc disposition |
| **Orchestrator** | ran the JVM suite for real; verified the AAR, the `PlatformEvents` ABI and the command-socket path against the **core** checkout; verified settings-without-writers; ran the Android knowledge quality checks |

**Subsystems reviewed:** core integration and libbox API surface; `PlatformFacts` and the
device axis; screen seed and observation epoch; foreground ordering and reentrancy;
`BoxService`/`CoreLifecycle`; `VPNService`/`ProxyService` and TUN ownership;
notification generation gate; default-interface monitor and resolver; UI command-client
lifetime; page-scoped sessions; logs and coalescing; import/subscription and the raw-config
boundary; build, ABI splits, provenance gates, signing and release CI; tests and evidence.

**Commits deep-read:** `d2a2e98`, `d628ef0`, `ebd2f79`, `aec3893`, `ac94df1`, `a3d2321`,
`1a86247`, `51cb7f5`, `18320df`, `7275c0f`, `481e370`, `15c432d`, `36dee73`, `56eb255`,
`15b5ff1`, `3c65418`, `3813a23`, `d10c9a5`, `2cb6eb9`, `5d9f4f2`, `bff3ce6`, `74ff3ce`,
`bdb4ced`, `7d4218a`, `cf3dc09`, `e8bd186`, `24c8372`, `87b3358`, `67a6df0`, `060b009`,
`d3af634`, `0a299ce`, `7416c73`, `fb606bf`, `7b6aeb3`, `919089d`, `fb5a361`, `89951ae`,
`6442c95`, `f133dc9`, `25ac148`, `f6a91df`, `72beebc`, `a0e2722`, `c7cc03d`, `722d22b`,
`3c06b53`, `19817c5`, `61b807e`, `58b8309`, `f2cde01`, `1a0b506`, `22d9ab4`, `21c7f68`,
`2171167`. **All 49 validated: every one exists, is a commit, and is unambiguous.**

**Tests reviewed:** all 23 JVM test classes, plus the three CI negative suites
(`test_check_core_provenance.py`, `test_release_abi_guard.py`,
`test_release_publish_gate.py`) and `verify_release_bundle.py` / `measure_apk_signature.sh`.

**Docs reviewed:** `KNOWLEDGE/**` (9 files), `AGENTS.md`, `KNOWLEDGE_STAGE1_REPORT.md`,
`README.md`, `docs/ROUND8-REPORT.md`, `docs/android-screen-power-parity-final-report.md`.

**External evidence used:** the GitHub REST API for runs, caches, artifacts, releases and
tags — all 64 runs enumerated.

---

## C. Stage-1 quality

| Metric | Count |
|---|---|
| CONFIRMED claims | ~40 (ownership map, guards, CI gate chain, provenance separation, raw-config rule, scope statements) |
| CORRECTED claims | 11 (see §D) |
| MISSING knowledge added | 9 subsystems/materially-empty areas (see §D) |
| DOWNGRADED invariants | 2 (screen-seed rule → latent contract; UI-client verdict split out of the service-ownership bullet) |
| UPGRADED verified facts | 1 whole class — 254 JVM tests executed for real, upgrading every policy claim from "historically verified" to `VERIFIED_UNIT` |
| REMOVED duplication | 9 facts that appeared in 2–5 files, consolidated to one owner each |
| PROVENANCE additions | 3 supersession pairs, 1 revert, 1 refused branch, 3 orphan tips, 3 mis-cited commits, 3 tags off their version commit |
| Files added | 5 (`modules/platform-facts.md`, `lifecycle-services.md`, `import-subscription.md`, `tests-evidence.md`; `lifecycle-platform-facts.md` retained as a redirect) |
| Files rewritten | 9 |

The knowledge base grew from ~216 lines of prose to a denser set, but the *number of
top-level files did not grow*: one file was split, one was added for a subsystem that had
no home, and one became a redirect.

---

## D. Important corrections

Only the corrections that change what a future AI would do.

### D1. The Stage-1 claim that Python was unavailable is wrong

> **Stage 1:** "The Python-backed static gates were not run for this refresh because
> `python` is unavailable in the checked Windows environment (exit `9009`)."

`python` on `PATH` is a 0-byte Windows Store alias stub — that is the `9009`. A working
interpreter is available through the harness runtime, and the gate runs in seconds. The
Stage-1 report could have shipped its strongest available evidence and did not.

### D2. CI does not exist at HEAD — the whole release gate is unexecuted

- The last CI run is **#54 on `ae89388`**. **No run exists on any commit after it**; HEAD
  is 13 commits past.
- The **entire ABI/engine release gate was added after that run** (`25ac148`); the newest
  release-workflow run contains no such step.
- **Zero releases** across 15 tags; every release-workflow run was a dry run that skipped
  signing and publishing.
- Therefore: the 254-test result is `VERIFIED_UNIT` **locally**, not `VERIFIED_CI`; and
  row 3 of `ACCEPTANCE.md` describes a gate whose *current* form has never executed in CI.

This is the single most consequential correction: Stage-1's `ACCEPTANCE.md` presented the
gate chain as a working pipeline.

### D3. The only runtime evidence predates the baseline and tests a different core

Round-8 evidence is product `ae89388` / core `c35faabf` on an x86_64 emulator. The
baseline is 13 commits later with a different pin, and the emulator artifact is the
**secondary** ABI. `arm64-v8a` — the declared shipping target — has **never been executed
anywhere**. A green emulator run says nothing about the shipping ABI's engine, packaging
or JNI load path.

### D4. The release gate has a forgeable path, proved by fault injection

On the publish-preflight path the verifier compares each APK's engine against
`core_engine_sha256` **from `build-info.json`** — i.e. against itself. A bundle whose APKs
carry a tampered engine and whose metadata records those same digests **passes**
(`exit 0, bundle OK`). Adding the real AAR flips it to `exit 1`. Bounded today only by the
publish job consuming the build job's artifact, and by the publish job never having run.

Separately, `--require-aar` in the build job is asserted by nothing, so deleting that flag
would be invisible to the suite.

### D5. Three settings are readable but never writable

`Settings.allowBypass`, `Settings.autoRedirect` and `Settings.systemProxyEnabled` have
getters and setters but **zero assignment sites**, so per-app bypass, core auto-redirect
and the `VpnService` HTTP proxy are permanently off. `BoxService.onSetSystemProxy(enabled)`
**ignores its argument**. `Settings.dynamicNotification` (default `true`) is also never
written, so the dynamic-traffic notification cannot be turned off from the UI.

Whether this is unfinished work or a deliberate always-off policy is recorded nowhere.
Status `UNKNOWN`; do not add a writer without a product decision.

### D6. The raw-config boundary has an undocumented negative half

Stage-1 stated the positive rule ("raw config must not receive hidden routing/DNS
injection"). It omitted that **only a sing-box body can be applied at all**: Clash and
Xray bodies are detected and retained, then silently replaced by the generated minimal
config. A user importing a Clash config as "raw" does not get their config.

### D7. `command.sock` is not a liveness oracle — verified against the core

The consumer probe's path is right (`basePath` = `filesDir`), but
`CommandServer.Close()` does **not** remove the socket while `Start()` does, so the file
survives a clean stop and the probe keeps answering "core is running". Verified in the
pinned core revision, not inferred from the Kotlin wrapper.

### D8. Provenance mis-citations inside the knowledge base

| Claim | Reality |
|---|---|
| screen-snapshot rule attributed to `36dee73`, `15b5ff1`, `481e370` | `36dee73`/`15b5ff1` never touch `ScreenFactState.kt`; the correct set is `7275c0f`, `481e370`, `15c432d` |
| `ac94df1` cited as pass-through provenance | it is the **opposite** pole — it introduced pass-through *with* built-in rule injection and a user switch |
| page-scoped monitoring attributed to `060b009`, `7416c73` | only `060b009` is page scoping; `7416c73` is full log retention with coalesced snapshots |
| one "canonical lifetime" bullet covering core and UI socket | two different axes; the UI verdict cannot revive the core or the notification |

### D9. `v0.5.10` is not an implementation baseline

Its tree is the three-core app (`MihomoCore`, `XrayCore`, `ClashApiClient`,
`SidecarProcess`, `TProxyService`, `CoreKind`), it has no `coreCommit`, and it is 88
commits behind. Stage-1 listed it in the same table as two single-core SHAs with no
architectural qualifier.

### D10. Gaps no Stage-1 file recorded

- **The screen-seed rule is latent, not live.** The receiver has no `Handler`, so the
  overwrite is unreachable on today's call path. The source says so explicitly; the
  knowledge base stated it as a live behavioural property.
- **The tests were not runnable as written.** Everything needed is cached locally, so
  `--offline` works. This is now a recipe in `modules/tests-evidence.md`.
- **Probe artifacts produce false negatives** (recorded in the reports, absent from
  KNOWLEDGE): `input keyevent 26` is a *toggle*; `logcat -d` returns only the ring-buffer
  remainder so count-differences can be 0 or negative; `adb shell` traffic bypasses the
  tunnel; the AVD charges so Doze never engages.
- **`dup_module_scope.py` and similar guards are weaker than they look** — see §F.
- **Repository hygiene:** `LICENSE` is MIT while `bg/DefaultNetworkListener.kt` carries two
  third-party copyright headers and states it was ported from `sing-box-for-android`
  (GPLv3). No commit reconciles this. Recorded as a follow-up, not fixed.
- **Four product names** coexist with no reconciling commit: repo/APK `satelite-one`,
  public brand `Jiejiebox`, package `com.interstellar.proxy`, and a live "Jiejiebox's own
  UI process" string in `ProxyCore.kt`.

---

## E. Commit provenance

**Newly mapped clusters** (added to `COMMIT-INDEX.md`): the multi-core era and its
removal; the raw-config polarity reversal; the platform-facts/device-axis series; the
screen-seed series including its own self-supersession; the foreground ordering series;
the UI-client-lifetime series; the service-ownership series; the notification series; the
network-monitor series; the log pipeline; the traffic-measurement revert; the
provenance/gate series; the CI series; the documentation/hygiene series.

**Supersession clusters:** `481e370 ⊃ 7275c0f`; `7d4218a ⊃ 51cb7f5/5d9f4f2/74ff3ce`;
`bdb4ced ⊃ 5d9f4f2/74ff3ce`; `24c8372 ⊃ e8bd186`; `7b6aeb3`/`fb606bf ⊃ 7416c73`;
`ed014c6 ⊃ 5d9f4f2`; `d628ef0`/`ad91f44`/`2f0bcbf ⊃ d2a2e98`/`ebd2f79`/`1a0b506`.

**Reverts:** `fb5a361` reverts the `clash_api` half of `919089d` (its stated premise was
false) while keeping its "unknown ≠ 0" semantics.

**Absorbed:** `a0e2722` absorbed the branch `ci/unsigned-dryrun-min-permissions-rebased`;
both rebases survive as non-ancestors.

**Refused:** `f6a91df` refused `fix/release-core-pin`; its tip `5445f1b` is a dangling
one-line re-pin to `2fd57543a…` that an earlier round declined.

**Orphan tips, patch-equivalent to commits now on `main`** (proved with
`git patch-id --stable`): `4791101`↔`25ac148`, `e413c59`↔`d10c9a5`, `baa9958`↔`15b5ff1`.
`main` was assembled by branch **and cherry-pick**, not by merging; no merge commit
records why. These three commits and `5445f1b`/`e0fe962`/`2334bad` are reachable **only
through the reflog** — an aggressive `git gc` erases them. This is now a caution in
`COMMIT-INDEX.md`.

**Tag boundaries:** 15 tags, only `v0.4.0`/`v0.4.1` annotated. Three tags sit **off** their
version commit (`v0.3.0` +29, `v0.4.0` +1, `v0.5.3` +1) — the exact case a later CI check
was written to audit, and it is real here. `coreCommit` moved four times:
`89951ae` → `a3d2321` → `6442c95` → `f133dc9`.

---

## F. Evidence quality

| Level | What holds it |
|---|---|
| **Source-only** | the ownership map, guards, TUN option gates, config shape, AIDL ordinals |
| **Static** | the three CI negative suites, `verify_release_bundle.py`, `check_core_provenance.py` on the real AAR (exit 0) and with the old pin (exit 1), and the stripped-engine fault injection (exit 1) |
| **Unit** | **254 tests / 23 classes / 0 failures**, executed during this review at the production baseline |
| **CI** | **only at `ae89388`** (run #54). Nothing newer. The current release-gate form has never run in CI |
| **Emulator** | round 8 only — product `ae89388`, core `c35faabf`, `emulator-5554`, API 36, **x86_64** |
| **Device** | **none. `VERIFIED_DEVICE` = 0.** No device model, no `ro.product.model`, no `adb devices` output anywhere in the repository |
| **Unverified** | the pinned core is rebuildable from source (two documents contradict each other, §H); `arm64-v8a` execution; the release gate's publish path |
| **Needs device** | OEM Doze, real unlock, real `onRevoke`, cellular/handover, signed-release installation |

### Gates weaker than they appear

- The JVM suite proves *models and source text*, not Android APIs:
  `unitTests.isReturnDefaultValues = true`, no Robolectric, no instrumentation source set.
  Four test classes read production source or reflect into it, so they are
  `VERIFIED_SOURCE` in a test's clothing.
- The repository's own comments record that **twelve minimal wiring breaks produced zero
  red** on a 230-test suite, and that a whole suite once passed with the P0-A defect
  restored. A green suite is not evidence of correct wiring.
- `test_release_abi_guard.py` cases N5 and N5b construct **byte-identical fixtures**, so
  N5b is a duplicate rather than the "forged metadata" case its comment claims.
- Timing/flakiness is real: 16 `Thread.sleep` sites, ~50 latches, nine `repeat(200)`
  real-thread races, and a process-wide singleton reset in `@After`. Run #53 went red on a
  flaky assertion in exactly this area.

---

## G. Docs disposition

Stage-1 classified `README.md` as KEEP and the two `docs/` reports as ARCHIVE, and
deleted nothing. **This round changes two dispositions.**

| Document | Stage-1 | Stage-2 | Why |
|---|---|---|---|
| `README.md` | KEEP | **KEEP-WITH-CAVEATS** | it is the build/release entrypoint, but its build recipe predates `89951ae`: it never copies `libbox.provenance` and never mentions `version.properties:coreCommit`, so following it verbatim cannot satisfy the release provenance gate |
| `docs/ROUND8-REPORT.md` | ARCHIVE | **KEEP-EVIDENCE (immutable)** | it is the *only* source for every runtime claim in `ACCEPTANCE.md`. Filing it ARCHIVE while citing it as evidence made the runtime row unbacked by anything a reader could find. It also carries the cache-hit caveat, the probe traps and the forbidden-verdict list |
| `docs/android-screen-power-parity-final-report.md` | ARCHIVE | **KEEP-EVIDENCE** | same reason, plus the `BLOCKED_BY_CORE_ABI` resume-edge finding and the lock-nesting audit with its stated limits. **Ordering hazard:** `ROUND8-REPORT.md` §G *edits* claims of this report, so where they disagree the round-8 document is authoritative — now stated in the KB |

High-value facts absorbed this round: the identity chain (`ae89388` / `c35faabf` /
run `38006795028` / artifact `11651178888` / size `104735665` / digest `bf738e4b…`, which
reconciles byte-for-byte); the emulator's disqualifying properties; the probe traps; the
platform-blocked items with their mechanical reasons; and the three-way split of "blocked"
into hardware-absent / platform-blocked / means-infeasible.

**No document was deleted or moved.** No `NOT CURRENT SOURCE OF TRUTH` banner was added:
the knowledge base now states the ordering rule instead, which is checkable.

---

## H. Follow-up (production code — not fixed this round)

| # | Item | Where |
|---|---|---|
| 1 | `SERVICE_STOPPED` is broadcast **before** `releaseCore()`, so the UI can show `Stopped` while the TUN is still up | `bg/BoxService.kt` |
| 2 | Three settings have no writer; `onSetSystemProxy(enabled)` ignores its argument | `data/Settings.kt`, `bg/BoxService.kt`, `bg/VPNService.kt` |
| 3 | `dynamicNotification` has no writer, so the setting cannot be changed from the UI | `bg/ServiceNotification.kt` |
| 4 | `LocalResolver` hard-errors with no default interface; `DefaultNetworkMonitor.stop()` does not clear the stale one | `bg/LocalResolver.kt`, `bg/DefaultNetworkMonitor.kt` |
| 5 | `command.sock`'s existence is used as a liveness probe, but the core does not remove it on close | `data/net/SubscriptionFetcher.kt`, `data/net/AppUpdateChecker.kt` |
| 6 | The publish-preflight verifier can be satisfied by self-consistent forged metadata (proved by fault injection) | `.github/scripts/verify_release_bundle.py`, `release-apk.yml` |
| 7 | `--require-aar` is asserted by nothing, so the strongest check can be dropped by a workflow edit | `.github/workflows/release-apk.yml` |
| 8 | `test_release_abi_guard.py` N5/N5b are byte-identical fixtures | `.github/scripts/test_release_abi_guard.py` |
| 9 | A Clash/Xray raw import is silently replaced by the generated config | `data/SubscriptionRepository.kt` |
| 10 | `MainActivity` is portrait-locked; a landscape/rotation acceptance item is therefore `N/A` rather than untested | `AndroidManifest.xml` |
| 11 | `LICENSE` is MIT while a source file carries GPLv3-derived third-party headers | `LICENSE`, `bg/DefaultNetworkListener.kt` |
| 12 | Four product names coexist with no reconciling commit | repo, `README.md`, `app/build.gradle.kts`, `core/ProxyCore.kt` |
| 13 | The two `docs/` reports contradict each other on the local AAR's byte count (29,217,593 vs the actual 118,160,367) | `docs/android-screen-power-parity-final-report.md` |
| 14 | A semantic resume edge needs an explicit core API; `CommandServer.wake()` means un-pause, so mapping `SCREEN_ON` onto it would wake a locked device | core repo |
| 15 | `Settings.accentId` defaults to `"green"`, which is not one of the presets | `data/Settings.kt`, `ui/theme/Accents.kt` |
| 16 | `SubscriptionRepository.selectNode` is dead, leaving two node-selection mechanisms | `data/SubscriptionRepository.kt` |

---

## I. Knowledge quality gate

No script was added. The Stage-1 base had no self-check and the repository already carries
17 gate scripts plus three negative suites; adding an eighteenth would be the "knowledge
base becomes a build system" failure the brief warns about. Instead the checks the brief
suggests were run **by hand** this round and their *results* are recorded:

| Check | Result |
|---|---|
| Every file `AGENTS.md` and `KNOWLEDGE/README.md` point at exists | **pass** — after this refresh; `README.md`'s module table was rewritten to match the real file set |
| The production baseline SHA is a valid Git object | **pass** — and it is the last production commit, verified by `git diff --name-only 2cb6eb9..HEAD` touching only `*.md` |
| Every SHA in `COMMIT-INDEX.md` exists | **pass** — 49/49 validated by the Git auditor |
| No dangling module links | **pass** |
| Status words are from the declared vocabulary | **pass** — `README.md` now carries the full ladder and each row names its environment |
| No duplicate baseline declarations | **pass** — `BASELINE.md` is the sole owner; other files cross-link |
| No stale Stage-1 path | **pass** |

The one check that found real work was the first: `KNOWLEDGE/README.md`'s module list did
not match the file set, and `AGENTS.md`'s standing instruction to "inspect the pinned
sing-box revision" was **unexecutable** — no checkout, no clone command, and the SHA is a
foreign object. `BASELINE.md` now carries the fetch command.

---

## J. Final state

```text
KNOWLEDGE_STAGE2 = COMPLETE

REPO_KIND = ANDROID

PRODUCTION_CODE_CHANGED = NO
UI_CHANGED = NO
RUNTIME_BEHAVIOR_CHANGED = NO
GIT_HISTORY_REWRITTEN = NO

PRODUCTION_BASELINE_VALID = YES
STAGE1_CLAIMS_REAUDITED = YES
COMMIT_PROVENANCE_REINFORCED = YES
INVARIANTS_REAUDITED = YES
EVIDENCE_LEVELS_REAUDITED = YES
CROSS_FILE_CONSISTENCY = PASS
FUTURE_AI_INCREMENTAL_READ = PASS

READY_FOR_FINAL_KNOWLEDGE_REVIEW = YES
```

`READY_FOR_FINAL_KNOWLEDGE_REVIEW = YES` is justified only in the sense of *internally
consistent*: source, Git, knowledge, acceptance and evidence now agree with each other.
It is **not** a statement that the product is release-ready. There is no release baseline,
CI does not cover HEAD, the shipping ABI has never been executed, and no physical device
has ever run this code.
