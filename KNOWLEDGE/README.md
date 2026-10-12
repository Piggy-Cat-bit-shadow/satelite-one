# Jiejiebox knowledge baseline

**Knowledge Baseline + Git Delta = Current AI Context.** This directory is a
high-cost reconstruction cache, not a substitute for source. Source and Git override
it whenever they disagree.

## Read order

1. `BASELINE.md` — pins. Take the **Production implementation baseline** SHA from it.
2. `PROJECT.md`, `ARCHITECTURE.md`, `INVARIANTS.md` — scope, ownership, non-negotiables.
3. Then **only** the module files whose ownership domain the task touches.
4. `ACCEPTANCE.md` before asserting any test, device or release outcome.
5. `COMMIT-INDEX.md` only to find a specific change; it is navigation, not evidence.

## The delta loop

```bash
BASE=$(grep -oE '[0-9a-f]{40}' KNOWLEDGE/BASELINE.md | head -1)
git log  --oneline $BASE..HEAD
git diff --stat    $BASE..HEAD
```

Classify that delta **before** reading anything deeply:

| Delta kind | Meaning for the knowledge base |
|---|---|
| production code | the affected module is stale; re-read source, do not re-read the whole base |
| tests / CI / release scripts | `ACCEPTANCE.md` is stale for those claims |
| documentation only | **no** runtime change; only the affected knowledge file is stale |
| core pin change | `BASELINE.md` pins and `modules/core-integration.md` are stale |

A documentation-only commit is never evidence of a runtime change.

## Module map

| Module | Owns |
|---|---|
| `modules/core-integration.md` | the app↔libbox boundary, command socket, core pin |
| `modules/platform-facts.md` | screen / foreground / trim / user-present facts, listener ordering, delivery lane |
| `modules/lifecycle-services.md` | `BoxService` ownership, `CoreLifecycle`, `VPNService`, `ProxyService`, notification |
| `modules/vpn-network.md` | TUN establishment, default-interface monitor, resolver, protection |
| `modules/ui-data.md` | UI command-client lifetime, `AppViewModel`, view models |
| `modules/import-subscription.md` | config import, raw pass-through vs generated config, subscriptions |
| `modules/build-release.md` | Gradle, ABI splits, provenance gates, signing, release CI |
| `modules/tests-evidence.md` | what the tests actually prove, how to re-run them, gate limits |
| `modules/lifecycle-platform-facts.md` | **redirect only** — that file was split into `platform-facts.md` and `lifecycle-services.md`; kept so older references still resolve |

## Evidence vocabulary

Use exactly these, and never promote one to another:

```text
VERIFIED_SOURCE   read directly in source at the stated revision
VERIFIED_GIT      proved by a diff, tree or ref
VERIFIED_STATIC   an executable audit/script produced this result
VERIFIED_UNIT     unit tests were actually executed
VERIFIED_CI       a recorded CI run produced this result
VERIFIED_EMULATOR an emulator produced this result
VERIFIED_DEVICE   a physical Android device produced this result
INFERRED          deduced; say from what
UNVERIFIED        asserted but not checked
UNKNOWN           could not be determined; say what is missing
BLOCKED_BY_ENVIRONMENT
NEEDS_DEVICE
EXTERNAL_CHECK_REQUIRED   needs the core checkout or another repository
```

Forbidden inferences: source inspection ≠ runtime proof; a successful build ≠ behaviour
proof; a static audit ≠ device parity; an emulator ≠ a physical device.

## Rules

- Normal feature work **must not** refresh this directory. Refresh it only in an
  explicit Knowledge Refresh task, and then re-pin `BASELINE.md` and the modules the
  delta actually touched.
- Do not add a file per finding. Correct and compress the existing files first; add a
  file only when an existing structure cannot express a subsystem.
- Every SHA, path and claim added here must cite where it was verified.
- `KNOWLEDGE_STAGE1_REPORT.md` and `KNOWLEDGE_STAGE2_REINFORCEMENT_REPORT.md` describe
  how this base was built. They are history, not current truth.
