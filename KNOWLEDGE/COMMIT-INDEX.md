# Commit index

**Navigation, not evidence.** This index is a curated subset: the full history is 199
commits on one linear branch. When the index does not cover a change you care about, use
`git log --oneline` — do not conclude the change does not exist.

**No merge commits, no submodules, no upstream remote.** The root commit
`3b7192425421ca6639cc22af869a99e1f3130688` (2026-08-27) is a LICENSE-only shell. There is
no upstream lineage; fork-owned history begins at the root.

Labels: topic = FEATURE FIX HARDENING PERF MEMORY COMPAT LIFECYCLE NETWORK UI
CORE-INTEGRATION TEST AUDIT CI BUILD RELEASE DOC REPOSITORY-HYGIENE.
Provenance = OWN ADAPTED UPSTREAM-ABSORBED CORE-COORDINATED SUPERSEDED ARCHIVE-ONLY.
A commit may carry several.

## Product boundary and the removal of the multi-core design

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `9212584`, `cfcda17`, `2e3c560`, `dfd685c`, `8818a53`, `926d6cd`, `47d1b77`, `b26d590` | FEATURE, COMPAT | SUPERSEDED | the multi-core era: `CoreKind`/`ProxyCore`, a mihomo sidecar, a pivot to a hev JNI bridge (because `ProcessBuilder` children do not inherit the TUN fd), then Xray |
| `d2a2e98` | CORE-INTEGRATION | OWN, SUPERSEDED-the-above | **单内核化**: deletes Mihomo/Xray/ClashApi/Sidecar/TProxy, the sidecar TUN, `Settings.coreKind`, `tools/fetch_cores.py` and ~32 MB of geodata assets |
| `d628ef0` | REPOSITORY-HYGIENE | OWN | dead-code sweep after `d2a2e98` |
| `1a0b506` | REPOSITORY-HYGIENE | OWN | deletes `SmartSwitchEngine` and `DirectPing` — "latency has one source" |
| `22d9ab4` | DOC, UI | OWN | public brand becomes **Jiejiebox** |
| `21c7f68` | BUILD | OWN | APK prefix `interstellar-` → `satelite-one-`; `applicationId` deliberately **not** changed |

## Raw config vs policy injection

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `ac94df1` (v0.5.8) | FEATURE | **SUPERSEDED** | added pass-through **with** built-in rule injection and a global "use raw config" switch — the *opposite* pole of today's behaviour. Do not cite it as current provenance |
| `ebd2f79` | REFACTOR | OWN | byte-for-byte pass-through; deletes `RawConfigApplier`, the rules stores, the DNS overrides and the `.srs` assets. `Settings` fields 30→22; string keys 423→304 per language |
| `d8b3093`, `ad91f44` | DOC, FIX | OWN | removes UI text still claiming injection |
| `aec3893` | REFACTOR | OWN | deletes the user switch entirely; format detection becomes automatic via `Libbox.checkConfig`; drops the tun stack dependency |

## Platform facts, device axis and the screen seed

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `a3d2321` | CORE-INTEGRATION | CORE-COORDINATED | introduces `PlatformFacts`/`PlatformFactSink` and **retires** the Doze-driven `pause()`/`wake()` path; also moves the core pin |
| `1a86247` | HARDENING | OWN | session token; single-consumer channel; `appForeground` starts **false** |
| `51cb7f5` | HARDENING | OWN | non-null token; refuse-to-unbind-unless-current; 5 s bounded drain; introduces `CoreLifecycle` |
| `4d15e3b` | DOC | OWN | makes the platform limit explicit and observable |
| `18320df` | FIX | CORE-COORDINATED | stages `CommandServer` ownership **before** it can fail; stop losing teardown failures |
| `7275c0f` | FIX | OWN, partly **SUPERSEDED** by `481e370` | first screen-seed fix; its own message records that its first attempt failed its test |
| `481e370` | FIX | OWN | **supersedes `7275c0f`**: the epoch must be sampled *before* the read and must count observations, not transitions |
| `15c432d` | TEST | OWN | pins the seed wiring, the same-value observation and the pre-read epoch |
| `a5a6783`, `32cc6cc`, `1efaa8f` | TEST | OWN | every trim level forwarded verbatim; the network path has zero wake producers |

## Foreground ordering, reentrancy, UI client lifetime

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `3c65418` | FEATURE | OWN | gives the UI's command-socket client one owner; introduces `UiClientLifetime` |
| `3813a23` | FIX | OWN | P0-A: `connect()` undid the verdict it was just handed; P0-B: listeners ran **under** the lock |
| `36dee73` | FIX | OWN | releasing the lock lost the serialization; sequence tickets added |
| `56eb255` | FIX | OWN | `foregroundDelivered` was `0L`, so ticket 1 concluded "predecessor already delivered" — reachable on the first pair after every process start |
| `15b5ff1` | FIX | OWN | a reentrant frame published a lower ticket, moving the watermark **backwards** and stranding the next notification |
| `d10c9a5` | FIX | OWN | `startProxy()`'s direct `connect()` bypassed the verdict gate |
| `2cb6eb9` | TEST | OWN | composes the startProxy guard with the foreground fact guard — **the production baseline** |

## Service lifecycle and ownership

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `5d9f4f2` | FIX | OWN | a destroyed Service instance is terminal; listener keyed by owner, not the singleton |
| `bff3ce6` | FIX | OWN | `published` never cleared |
| `74ff3ce` | FIX | OWN | start-attempt watermark; a stale stop cannot converge over a newer generation |
| `bdb4ced` | FIX | OWN | claim the start token on main **before** `status = Starting`; one decision, all-or-none convergence |
| `7d4218a` | FIX | OWN | two individually-correct halves deadlocked; replaced by three independent rules, generation counter removed |
| `cf3dc09` | FIX | OWN | `onRevoke` no longer blocks the main thread (it self-deadlocked) |

## Notification, network monitor, logs

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `e8bd186` | FIX | OWN | a cancelled scope is unrevivable; `NotificationPublishGate` introduced |
| `24c8372` | FIX | OWN | one shared handler authorised old callbacks under the new session; session per generation |
| `87b3358` | FIX | OWN | a receiver could register after `close()` |
| `67a6df0` | FIX | OWN | the notification gets its own scope; cancel last |
| `ed014c6` | FIX | OWN | **corrects** round 5: the listener object *is* the ownership proof; `InterfaceListenerRegistry` with `===` |
| `8432caf` | FIX | OWN | resolution ran on the main looper with up to nine `Thread.sleep(100)`; the loss branch returned before claiming an epoch |
| `060b009` | PERF | OWN | connections monitoring becomes page-scoped |
| `d3af634`, `0a299ce` | FIX | OWN | `SessionGate`; check inside the lock; `commitConnected` through the same gate |
| `7416c73` | PERF | OWN | `LogRingBuffer` + 75 ms coalescing (full retention — *not* page scoping) |
| `fb606bf`, `7b6aeb3` | FIX | OWN | first entry parked on the oldest line; a full ring stopped firing and a line could be marked published but never displayed |
| `919089d` | FIX | OWN, **partly REVERTED** | added an empty `clash_api` block claiming the traffic manager needs it |
| `fb5a361` | FIX | OWN | **reverts that half**: the premise was false (`PlatformLogWriter` is always non-nil for libbox); the real cause was a measurement error. Keeps "unknown ≠ 0" |

## Core provenance, release gates, CI

| Commit | Topic | Provenance | Lasting point |
|---|---|---|---|
| `89951ae` | RELEASE | CORE-COORDINATED | `BuildConfig` and the packaged `libbox.so` disagreed; introduces `libbox.provenance` as the single source and a byte-search gate that **fails** rather than warns; introduces `coreCommit` |
| `6442c95` | RELEASE | OWN | moves the pin; states the tree is **RED on purpose** and why `verifyCoreProvenance` cannot see it (self-consistency, not the pin) |
| `f133dc9` | RELEASE | OWN | moves the pin to the SHA the AAR is actually built from |
| `25ac148` | RELEASE | OWN | root cause was a **missing gate**, not a missing AAR: per-ABI own-engine requirement + `_zipcontent.py` + `--require-aar` |
| `f6a91df` | CI | OWN | an evidence **misattribution** is corrected; the Python gate is added pre-build |
| `72beebc` | CI | OWN | the Python and Gradle gates are not duplicates |
| `a0e2722` | CI | OWN | corrects its own prior error: a release with no `signing.properties` is **unsigned**, not debug-signed |
| `c7cc03d` | CI | OWN | the publish gate was fail-open (glob → empty array → "all 0 APKs verify", exit 0) |
| `722d22b` | CI | OWN | verified bytes and uploaded bytes were two independent path expressions |
| `3c06b53` | CI | OWN | checksums were generated **before** the rename, so the manifest described nonexistent files |
| `19817c5` | CI | OWN | one step owns copy → hash → describe → prove |
| `61b807e` | CI | OWN | the verified list was printed but the upload enumerated again; a verifier tolerated unlisted files |
| `58b8309` | CI | OWN | lets a dispatch pick a core revision for diagnosis |
| `f2cde01` | CI | OWN | dev CI becomes manual-dispatch only |

## Documentation and hygiene series

`ffbd6aa`, `50140a8`, `c969126`, `fa5fb04`, `cae13dd`, `59b03f1`, `2b206cd`,
`017fff7`, `dc80505`, `bffb8ad`, `54a3eb0`, `e0284cc`, `6ea83f8`, `e1ec9f8`,
`6864a1e`, `942b6fe` — all touch only `docs/**`, `KNOWLEDGE/**` or `AGENTS.md`
(verified by `--name-only`). The pattern is to supersede by **adding a labelled
correction**, never by rewriting history.

`cd9153d` adds `FUNDING.yml`; `2171167` touches `.gitignore` only — it blocks committing
a stub AAR that would make the provenance gate meaningless.

## Supersession, revert and absorption pairs

| Pair | Relationship |
|---|---|
| `481e370` ⊃ `7275c0f` | supersedes part of it (epoch sampling and counting) |
| `7d4218a` ⊃ `51cb7f5`/`5d9f4f2`/`74ff3ce` | supersedes the generation-based publish gate and deletes the second counter |
| `bdb4ced` ⊃ `5d9f4f2`/`74ff3ce` | token claim moves to main; `pendingRestart` deleted |
| `24c8372` ⊃ `e8bd186` | supersedes part of the notification gate |
| `7b6aeb3`, `fb606bf` ⊃ `7416c73` | supersede parts of the log pipeline |
| `ed014c6` ⊃ `5d9f4f2` | corrects round 5's ownership assumption |
| `fb5a361` ⊃ `919089d` | **reverts** the `clash_api` half, keeps the semantics |
| `d628ef0`, `ad91f44`, `2f0bcbf` ⊃ `d2a2e98`/`ebd2f79`/`1a0b506` | sweep leftovers |
| `a0e2722` absorbs a branch | `ci/unsigned-dryrun-min-permissions-rebased`; both rebases survive as non-ancestors |
| `f6a91df` **refuses** a branch | `fix/release-core-pin`; tip `5445f1b` is a dangling one-line re-pin an earlier round declined |

## Hidden archaeology, and why it is fragile

Five topic branches were deleted and survive only in the reflog. Three left **orphan tips**
that are patch-equivalent (proved with `git patch-id --stable`) to commits now on `main`:

| Orphan | Equivalent to | Subject |
|---|---|---|
| `4791101` | `25ac148` | release ABI guard |
| `e413c59` | `d10c9a5` | startProxy lifetime guard |
| `baa9958` | `15b5ff1` | foreground ordering guard |

`main` was therefore assembled by branch + cherry-pick, not by merging, and **no merge
commit records why**. Three commits — `5445f1b` (the refused re-pin), `e0fe962` and
`2334bad` (rebases of the absorbed CI branch) — are reachable only through the reflog. A
`git gc --prune` erases the only surviving evidence of them. **Do not run an aggressive
`git gc` in this clone.**

## Tag boundaries

15 tags, all lightweight except `v0.4.0` and `v0.4.1`. `versionName` matches each tag.

**`v0.5.10` / `6f4124c` is ARCHITECTURALLY SUPERSEDED.** Its tree is the *three-core* app
(it contains `MihomoCore`, `XrayCore`, `ClashApiClient`, `SidecarProcess`, `TProxyService`
and `CoreKind`), it has **no `coreCommit`**, and it is **88 commits** behind HEAD. It is
the last *released* product, not a description of this code. Never use it as an
implementation baseline.

Three tags are **not** on their version commit (`v0.3.0` +29, `v0.4.0` +1, `v0.5.3` +1) —
the case a CI check was later added to audit, and it is real in this history.

`describe HEAD` = `v0.5.10-88-g942b6fe`: the single-core client, the fact/lifecycle gates
and the provenance/ABI release gates are all **unreleased**.
