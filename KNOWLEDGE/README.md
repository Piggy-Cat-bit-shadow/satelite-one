# Jiejiebox knowledge baseline

**Knowledge Base + Git Delta = Current AI Context.** Source and Git override this costly-to-reconstruct context.

1. Read BASELINE, PROJECT, ARCHITECTURE, and INVARIANTS.
2. Run `git log --oneline <baseline>..HEAD` and `git diff --stat <baseline>..HEAD`.
3. Read only affected modules plus source/tests; inspect the pinned core for core behaviour.
4. Read ACCEPTANCE before asserting test, device, or release outcomes.

Normal work must not update this directory every commit. Status vocabulary: VERIFIED, INFERRED, UNVERIFIED, UNKNOWN, BLOCKED_BY_ENVIRONMENT, NEEDS_REAL_DEVICE.

Modules: core-integration, lifecycle-platform-facts, vpn-network, ui-data, and build-release. COMMIT-INDEX is navigation; KNOWLEDGE_STAGE1_REPORT states the evidence boundary.
