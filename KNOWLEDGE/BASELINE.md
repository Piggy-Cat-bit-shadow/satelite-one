# Baseline

## Four different things that must not be confused

| Field | Value | How to re-verify |
|---|---|---|
| **Production implementation baseline** | `2cb6eb9b8e3721036534ef84b1e4352369ac1484` | `git log -1 2cb6eb9` — the last production-code commit this knowledge base describes |
| Knowledge document revision | `942b6fe2195918be1f8f4c160cf7caf5af454d09` | `git log -1 942b6fe` — docs/evidence only; **not** a product SHA |
| Latest tag | `v0.5.10` → `6f4124c` | `git rev-parse v0.5.10^{commit}` |
| Working tree at Stage-2 review | clean | `git status --porcelain` |

`2cb6eb9` is the lower bound for code archaeology. Everything after it is
documentation or evidence:

```text
2cb6eb9  production baseline
   |-- 6864a1e  docs(android): establish stage-1 knowledge baseline
   `-- 942b6fe  docs(android): clarify knowledge delta and release evidence
```

```bash
git log --oneline 2cb6eb9..HEAD     # expect docs(...) commits only
git diff --stat  2cb6eb9..HEAD      # expect KNOWLEDGE/, docs/, AGENTS.md, reports only
```

**Do not chase the knowledge base's own commit SHA.** A later docs-only commit does
not move the production baseline and must not be reported as a runtime change. When
the knowledge base is refreshed after documentation-only work:

```text
Production baseline      unchanged
Knowledge reviewed date  updated
```

## Pins

| Pin | Value | Meaning |
|---|---|---|
| App version | `versionCode=16`, `versionName=0.5.10` (`version.properties`) | single source of version truth |
| Release core pin | `1965bbf351710b86722838ae61a91b62c97d29de` (`version.properties:coreCommit`) | the sing-box revision release CI fetches and builds |
| Core repository / branch | `Piggy-Cat-bit-shadow/sing-box` / `testing` | release resolves the **pin**; development CI follows `testing` |
| Local AAR producer record | `app/libs/libbox.provenance`: `commit == version == 1965bbf…` | must equal the pin; a mismatch fails `:app:verifyCoreProvenance` |
| Shipping ABIs | `arm64-v8a`, `x86_64` | `app/build.gradle.kts` `splits.abi`; `isUniversalApk=false` |

A tag is **not** proof that a release was built or published. `v0.5.10` records an
intent in history; only a release run's measured artifact evidence does that.

**Release baseline: none recorded.** No run has produced a fresh pinned release
artifact, so there is nothing to point at. A release baseline exists only once a
`v*` run's `release-bundle/` has been verified — see `ACCEPTANCE.md`.

## Core relationship (checked against the core checkout, not inferred)

The app talks to the core through two libbox surfaces. Both were verified against
the pinned revision rather than inferred from the Kotlin wrappers:

| Surface | App side | Core side | Contract |
|---|---|---|---|
| `PlatformInterface` (core → app) | `bg/PlatformInterfaceWrapper.kt` | `experimental/libbox` `PlatformInterface` | openTun, findConnectionOwner, interfaces, WIFI state, default-interface monitor |
| `PlatformEvents` (app → core) | `core/SingBoxCore.kt` via `Libbox.newPlatformEvents(server)` | `platform_events.go` | exactly `MemoryTrim(int32)`, `SetScreenOn(bool)`, `SetAppForeground(bool)`, `ReportDeviceWake()`, `Close()` — one bridge per `CommandServer` |
| Command socket | `utils/CommandClient.kt` | `command_server.go` `Start()`: `filepath.Join(sBasePath, "command.sock")` | the socket lives in `basePath`, which `InterstellarApplication` sets to `filesDir` |

Consequences worth remembering:

- The five `PlatformEvents` methods above are the whole app→core fact surface. A core
  bump that renames one breaks exactly one app file (`core/SingBoxCore.kt`).
- `CommandServer.Close()` does **not** remove `command.sock`, while `Start()` does.
  A stale socket file therefore survives a clean shutdown — see the caution in
  `modules/vpn-network.md` about treating the file's existence as liveness.

Do not summarize the core from here. Inspect the pinned revision for core semantics
(reload, pause/wake, level-vs-edge behavior, command operations).

## Evidence status

`VERIFIED_SOURCE` for SHAs, pins, ABI list and the `PlatformEvents` surface.
`VERIFIED_GIT` for the `2cb6eb9..HEAD` docs-only classification.
