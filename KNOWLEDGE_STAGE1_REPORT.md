# Knowledge Baseline Stage 1 report

| Item | Result |
|---|---|
| Repository / branch | `Piggy-Cat-bit-shadow/satelite-one` / `main` |
| Production implementation baseline | `2cb6eb9b8e3721036534ef84b1e4352369ac1484` |
| Documentation-only knowledge tip at capture | `6864a1ef9295d865ef09b10614ef657efb3a14f4` |
| Latest tag baseline | `v0.5.10` / `6f4124c` |
| Core pin / producer provenance | `1965bbf351710b86722838ae61a91b62c97d29de` / same |
| Commits scanned | 1,000+ reachable history; deep review of current single-core, lifecycle and release series |
| Production code changed | NO |
| Git history rewritten | NO |

The knowledge base captures client/core ownership, raw Android facts, VPN/network/service ownership, UI-client lifetime, import boundary, and release provenance. Cross-checks covered version properties, Gradle, workflows, provenance/release scripts, local producer record, source, tests and history. It explicitly separates AAR producer/binary identity, Gradle self-consistency, final per-ABI APK integrity, verified-bundle identity, and measured signing state; none stands in for the others. Earlier multi-core history is SUPERSEDED, not erased.

The implementation baseline deliberately remains the last production commit. At
capture, `2cb6eb9..6864a1e` is documentation/evidence only; a future reader must
still inspect `production-baseline..HEAD` and classify the delta rather than assume
that documentation commits are product changes.

## Existing-document classification

| Document | Classification | Rationale |
|---|---|---|
| README.md | KEEP | product/build/release entrypoint |
| docs/ROUND8-REPORT.md | ARCHIVE | auditable historical CI/emulator evidence, not current release truth |
| docs/android-screen-power-parity-final-report.md | ARCHIVE | historical evidence/limitations; preserve conservatively |

No old documents were deleted or moved. UNVERIFIED: a fresh current-pin release artifact. NEEDS_REAL_DEVICE: OEM Doze, cellular, actual revoke/unlock and signed-release installation. FOLLOW-UP: a semantic resume edge needs an explicit core API; do not overload wake.

```text
KNOWLEDGE_STAGE1 = COMPLETE
PRODUCTION_CODE_CHANGED = NO
GIT_HISTORY_REWRITTEN = NO
BASELINE_PINNED = YES
CORE_PROVENANCE_CAPTURED = YES
COMMIT_PROVENANCE_BUILT = YES
READY_FOR_INDEPENDENT_REVIEW = YES
```
