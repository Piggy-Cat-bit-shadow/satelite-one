# Jiejiebox knowledge baseline

**Knowledge Base + Git Delta = Current AI Context.** Source and Git override this costly-to-reconstruct context.

1. Read BASELINE, PROJECT, ARCHITECTURE, and INVARIANTS.
2. Set `BASELINE` to the full **Production implementation baseline** SHA in BASELINE,
   then run `git log --oneline $BASELINE..HEAD` and
   `git diff --stat $BASELINE..HEAD`.
3. Classify that delta before reading deeply: production, tests/CI/release, or
   documentation only. A documentation-only commit is not evidence of a runtime change.
4. Read only affected modules plus source/tests; inspect the pinned core revision for
   core behaviour. Do not infer a core API from Kotlin wrappers or old reports.
5. Read ACCEPTANCE before asserting test, device, or release outcomes.

Normal work must not update this directory every commit. Status vocabulary: VERIFIED, INFERRED, UNVERIFIED, UNKNOWN, BLOCKED_BY_ENVIRONMENT, NEEDS_REAL_DEVICE.

Modules: core-integration, lifecycle-platform-facts, vpn-network, ui-data, and
build-release. COMMIT-INDEX is navigation; KNOWLEDGE_STAGE1_REPORT states the
evidence boundary. The documentation-only tip is recorded separately in BASELINE
so the repository can retain a production baseline without pretending the knowledge
files describe an arbitrary future HEAD.
