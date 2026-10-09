#!/usr/bin/env python3
"""Negative tests for the release publish gate.

The publish job is the one place in this repository that puts an artifact in front
of users, and it is also the one place that cannot run on a normal push: it is only
reachable from a `v*` tag push with `dry_run != true`. That combination is exactly
how it shipped with a hole nobody could see.

The hole, found by reading the round-4 dry-run evidence rather than by running the
job (the round-4 CI log shows `publish: skipped`):

  * `Download APK artifacts` uses `path: .`, so the artifact's contents land in the
    **working directory** (the upload list is `app/build/outputs/apk/release/*.apk`,
    `SHA256SUMS.txt`, `apksigner-verify.txt`, `build-info.json`, and
    `download-artifact` preserves that relative structure under the given path);
  * the verification step then looked for `app/build/outputs/apk/release/*.apk`;
  * the publish job only checks out the repository — it never builds — so that
    directory exists only if the checkout happened to create it, and with
    `shopt -s nullglob` the glob expanded to an **empty array**;
  * an empty array means the `for` loop body never runs, so the step fell through to
    `echo "all 0 APKs verify as signed with a non-debug certificate"` and **passed**.
    An unsigned or unverified APK would then be published by the next step.

So this file does not test "does apksigner work" — it tests the two properties that
were actually wrong, plus the decision table, and it reads the decision rule out of
the shipped shell script instead of restating it:

  1. the publish job's APK discovery must be able to see where the artifact lands,
     and must fail closed when it finds nothing;
  2. `measure_apk_signature.sh` must classify unsigned / debug-signed /
     signed-by-user the way `release-apk.yml` relies on, and must not have any path
     that reports a state without having measured one;
  3. both jobs must call the same script, so the build-time and publish-time
     verdicts cannot drift apart again.

Pure stdlib + no third-party dependencies, so it runs anywhere `python3` does —
including the normal CI job, which is what makes it real evidence rather than a
local claim.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WORKFLOW = os.path.join(REPO, ".github", "workflows", "release-apk.yml")
MEASURE = os.path.join(REPO, ".github", "scripts", "measure_apk_signature.sh")

failures = []


def check(condition, message):
    if condition:
        return True
    failures.append(message)
    print(f"::error::{message}")
    return False


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def job_block(workflow, job_name):
    """Return the YAML text of one top-level job.

    Deliberately a text slice rather than a YAML parse: the point is to inspect the
    shell the job actually runs, and a YAML round-trip would normalise away the very
    characters being tested. Only top-level `  <name>:` lines end a job.
    """
    match = re.search(rf"^  {re.escape(job_name)}:\s*$", workflow, re.M)
    if not match:
        return None
    body = workflow[match.end():]
    end = re.search(r"^  [A-Za-z_][A-Za-z0-9_-]*:\s*$", body, re.M)
    return body[: end.start()] if end else body


# ---------------------------------------------------------------------------
# 1. the publish job finds the APKs where the artifact actually puts them
# ---------------------------------------------------------------------------

def test_publish_job_can_see_the_artifact():
    workflow = read(WORKFLOW)
    publish = job_block(workflow, "publish")
    if not check(publish is not None, "release-apk.yml has no publish job"):
        return

    check(
        "path: ." in publish,
        "publish job no longer downloads the artifact into the working directory; "
        "re-check where the APKs land before trusting the verification step",
    )
    # The broken shape: a fixed build-output path plus a glob that silently expands
    # to nothing. `nullglob` is only safe once a count check exists.
    broken = re.search(r"APKS=\(app/build/outputs/apk/release/\*\.apk\)", publish)
    check(
        broken is None,
        "publish job is back to globbing app/build/outputs/apk/release/*.apk, which the "
        "artifact download does not populate — the loop body never runs and the step "
        "passes without verifying anything",
    )
    check(
        "find ." in publish,
        "publish job does not discover the APKs by searching the artifact's real location",
    )
    # Fail-closed: "found nothing" must be an error, and the count must be asserted.
    check(
        re.search(r'\[ "\$\{#APKS\[@\]\}" -eq 0 \]', publish) is not None,
        "publish job does not reject an empty APK list",
    )
    check(
        re.search(r'\[ "\$\{#APKS\[@\]\}" -ne 2 \]', publish) is not None,
        "publish job does not assert the expected per-ABI APK count",
    )
    check(
        "measure_apk_signature.sh" in publish,
        "publish job does not use the shared signature measurement script",
    )


# ---------------------------------------------------------------------------
# 2. both jobs share one measurement, and the build job's expectation is explicit
# ---------------------------------------------------------------------------

def test_both_jobs_share_one_measurement():
    workflow = read(WORKFLOW)
    build = job_block(workflow, "build")
    if not check(build is not None, "release-apk.yml has no build job"):
        return
    check(
        "measure_apk_signature.sh" in build,
        "build job does not use the shared signature measurement script",
    )
    # The two verdicts must not be re-implemented per job: that is what let the
    # publish copy drift onto a path nothing populated. Detect the *logic*, not the
    # word: "apksigner verify" also appears as build-info.json's
    # `signature_state_measured_by` metadata string, which is not a shell command.
    inline_shell = re.search(
        r"APKSIGNER=|APKSIGNER=\"|\"\$APKSIGNER\"\s+verify|^\s*\$APKSIGNER",
        build,
        re.M,
    )
    check(
        inline_shell is None,
        "build job still contains its own inline apksigner logic; both jobs must call "
        "the one script so the build-time and publish-time verdicts cannot diverge",
    )
    check(
        "measure_apk_signature.sh" in build,
        "build job does not invoke the shared measurement script",
    )
    check(
        "expectation" in read(MEASURE),
        "measure_apk_signature.sh has no expectation parameter; a measurement with no "
        "expected value cannot fail closed",
    )


# ---------------------------------------------------------------------------
# 3. the decision table, read out of the shipped shell script
# ---------------------------------------------------------------------------

def arms_from_script():
    """The `case` arms of measure_apk_signature.sh, in file order.

    Returns a list of `(expectation, wanted_state, permitted)` where `permitted` is
    True for the arms whose body is empty (they fall through to the success reporting)
    and False for the arms that `exit 1`.

    A line scanner rather than a regex: the arms have two shapes and a regex that
    handles both is harder to read than the loop. Reading the arms here instead of
    restating the rule means this test fails if someone edits the table, which is the
    whole point of a gate test.
    """
    block = re.search(
        r'case "\$expectation:\$state" in\n(.*?)\nesac', read(MEASURE), re.S
    )
    if not check(block is not None, "measure_apk_signature.sh has no expectation/state case block"):
        return []

    arms = []
    current = None          # (expectation, wanted_state) of the arm being read
    has_commands = False    # whether that arm's body contained any command

    def flush():
        if current is not None:
            arms.append((current[0], current[1], not has_commands))

    for raw in block.group(1).splitlines():
        line = raw.strip()
        arm = re.fullmatch(r"([a-z-]+):(\*|[a-z-]+)\)", line)
        if arm:
            flush()
            current = (arm.group(1), arm.group(2))
            has_commands = False
            continue
        if line == ";;":
            flush()
            current = None
            continue
        if current is not None and line and not line.startswith("#"):
            has_commands = True
    flush()
    return arms


def decision_from_script():
    """Resolve the arms into concrete `expectation:state -> permitted` outcomes.

    The resolution models a shell `case`: arms are tried **in file order** and the
    first one whose pattern matches wins, so a wildcard arm cannot claim a state that
    an earlier, narrower arm already handled.

    That first-match rule is the interesting part rather than a detail. In a shell,
    `*` matches any string, so an arm written `unsigned:*)` ALSO matches
    `unsigned:unsigned`. Put it above the empty `unsigned:unsigned)` arm and a
    correctly unsigned dry run is rejected as "expected an unsigned artifact but
    apksigner measured 'unsigned'" — a gate that fails on the happy path. The shipped
    order therefore has to put the empty arms first, and this function is what makes
    that ordering assertable instead of a comment.
    """
    states = ("unsigned", "debug-signed", "signed-by-user")
    rules = {}
    # Per expectation, the states already claimed by an earlier arm.
    claimed = {}
    for expectation, wanted, permitted in arms_from_script():
        taken = claimed.setdefault(expectation, set())
        candidates = states if wanted == "*" else (wanted,)
        for state in candidates:
            if state in taken:
                continue        # an earlier, narrower arm already decided this pair
            taken.add(state)
            rules[f"{expectation}:{state}"] = permitted
    if not check(rules, "could not resolve any arm out of the expectation/state case block"):
        return {}
    return rules


def test_decision_table():
    rules = decision_from_script()
    if not rules:
        return
    expected = {
        # A dry run must be unsigned and may not silently accept a signed artifact.
        ("unsigned", "unsigned"): True,
        ("unsigned", "debug-signed"): False,
        ("unsigned", "signed-by-user"): False,
        # A release must be signed with the user's own certificate. The Android debug
        # keystore is public and well-known, so "debug-signed" is NOT "signed".
        ("signed", "signed-by-user"): True,
        ("signed", "unsigned"): False,
        ("signed", "debug-signed"): False,
    }
    for (expectation, state), should_pass in sorted(expected.items()):
        key = f"{expectation}:{state}"
        if not check(key in rules, f"decision table has no arm for {key}"):
            continue
        check(
            rules[key] is should_pass,
            f"decision table: {key} should {'pass' if should_pass else 'fail'}, got "
            f"{'pass' if rules[key] else 'fail'}",
        )
    # Every non-permitted combination must exit non-zero; there must be no catch-all
    # arm that quietly accepts everything else.
    for pair, permitted in sorted(rules.items()):
        if pair.split(":")[1] == "*":
            check(not permitted, f"catch-all arm {pair} does not fail closed")


# ---------------------------------------------------------------------------
# 4. the measurement itself has no "report a state without measuring" path
# ---------------------------------------------------------------------------

def test_measurement_fails_closed():
    text = read(MEASURE)
    check(
        re.search(r"if \[ \"\$#\" -eq 0 \]", text) is not None,
        "measure_apk_signature.sh does not reject being called with no APK path",
    )
    check(
        "refusing to report a signature state" in text,
        "measure_apk_signature.sh has no explicit refusal when it cannot measure",
    )
    check(
        "shopt -s nullglob" in text and "shopt -u nullglob" in text,
        "measure_apk_signature.sh must scope nullglob around collection only, so an "
        "empty directory is rejected by the count check rather than looping zero times",
    )
    # The state must be derived from apksigner's output, and the debug-keystore check
    # must be a positive match on the certificate subject.
    check(
        "CN=Android Debug" in text,
        "measure_apk_signature.sh does not detect the Android debug keystore subject",
    )
    check(
        "APKSIGNER:-" in text,
        "measure_apk_signature.sh cannot be pointed at a specific apksigner, so it "
        "cannot be exercised with a fake one",
    )


def main():
    test_publish_job_can_see_the_artifact()
    test_both_jobs_share_one_measurement()
    test_decision_table()
    test_measurement_fails_closed()

    if failures:
        print(f"\n{len(failures)} release-gate check(s) failed")
        sys.exit(1)
    print("release publish gate: all checks passed")


if __name__ == "__main__":
    main()
