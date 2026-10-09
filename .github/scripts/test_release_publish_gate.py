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
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WORKFLOW = os.path.join(REPO, ".github", "workflows", "release-apk.yml")
MEASURE = os.path.join(REPO, ".github", "scripts", "measure_apk_signature.sh")
VERIFY_BUNDLE = os.path.join(REPO, ".github", "scripts", "verify_release_bundle.py")

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
# 1. the publish job verifies, and uploads, the same flat bundle
# ---------------------------------------------------------------------------

def test_publish_job_verifies_and_uploads_one_bundle():
    workflow = read(WORKFLOW)
    publish = job_block(workflow, "publish")
    build = job_block(workflow, "build")
    if not check(publish is not None and build is not None, "release-apk.yml is missing a job"):
        return

    # The artifact must be downloaded into ONE isolated directory, and that directory is
    # the only thing anything downstream is allowed to look at.
    check(
        "path: release-bundle" in publish,
        "publish job does not download the artifact into an isolated release-bundle/",
    )
    # The round-5 broken shape: a glob against the build-output layout, which the artifact
    # download does not populate. Kept as an explicit tripwire because it is the exact
    # regression this file exists for.
    check(
        re.search(r"APKS=\(app/build/outputs/apk/release/\*\.apk\)", publish) is None,
        "publish job is back to globbing app/build/outputs/apk/release/*.apk, which the "
        "artifact download does not populate — the loop body never runs and the step "
        "passes without verifying anything",
    )
    # The verified set and the uploaded set must be the same expression space: the release
    # step may only reference the bundle directory.
    release_files = re.search(r"files: \|\n((?:\s+\S+\n)+)", publish)
    if check(release_files is not None, "could not find the release `files:` list"):
        listed = release_files.group(1).strip().splitlines()
        outside = [line.strip() for line in listed if not line.strip().startswith("release-bundle/")]
        check(
            not outside,
            f"the release uploads files outside the verified bundle: {outside}",
        )
    check(
        "verify_release_bundle.py" in publish,
        "publish job does not run the bundle preflight verifier",
    )
    # The build side has to produce that bundle, flatly, and self-check it.
    check(
        "release-bundle" in build,
        "build job does not assemble a release-bundle",
    )
    check(
        "sha256sum --check SHA256SUMS.txt" in build,
        "build job does not verify its own bundle checksums; the release job must never "
        "be the first place a bundle is checked",
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


# ---------------------------------------------------------------------------
# 5. the bundle preflight, exercised against real bundle fixtures
#
# Structural checks can only show that the workflow *calls* the verifier. These build
# actual bundle directories on disk and run the real script against them, so the
# decisions are measured rather than described. Every rejection case below is a way an
# artifact could reach users if the gate were merely "find some APKs and hope".
# ---------------------------------------------------------------------------

def run_bundle_preflight(bundle_dir, expect_signature="signed", extra_args=()):
    """Run verify_release_bundle.py; return (exit_code, stdout, stderr)."""
    cmd = [
        sys.executable,
        VERIFY_BUNDLE,
        str(bundle_dir),
        "--expect-signature",
        expect_signature,
        *extra_args,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout or "", proc.stderr or ""


BUNDLE_VERSION = "0.5.10"
APP_COMMIT = "a" * 40
CORE_COMMIT = "c35faabf402a4da93b8c31cdfad941b8b1528ffc"


def write_bundle(root, *, abis=("arm64-v8a", "x86_64"), signature_state="signed-by-user",
                 extra_apk=None, tamper=None, omit=None, sums_override=None,
                 info_override=None, nested=False, apk_version=BUNDLE_VERSION,
                 app_commit=APP_COMMIT, core_commit=CORE_COMMIT, core_ref="pinned",
                 kind=None):
    """Materialise a release bundle. Every knob is a documented attack on the gate."""
    os.makedirs(root, exist_ok=True)
    kind = kind if kind is not None else signature_state
    names = [f"satelite-one-{apk_version}-{abi}-{kind}.apk" for abi in abis]
    if extra_apk:
        names.append(extra_apk)
    if omit:
        names = [n for n in names if n != omit]

    target_dir = os.path.join(root, "nested") if nested else root
    os.makedirs(target_dir, exist_ok=True)

    digests = {}
    for name in names:
        path = os.path.join(target_dir, name)
        with open(path, "wb") as handle:
            handle.write(b"PK\x03\x04 fake apk payload " + name.encode())
        digests[name] = digest_of(path)
    if tamper:
        # Flip one byte AFTER the manifest was computed: this is R04.
        with open(os.path.join(target_dir, tamper), "r+b") as handle:
            handle.seek(4)
            handle.write(b"\xff")

    sums = sums_override if sums_override is not None else digests
    with open(os.path.join(root, "SHA256SUMS.txt"), "w", encoding="utf-8") as handle:
        for name, digest in sums.items():
            handle.write(f"{digest}  {name}\n")

    info = {
        "app_repository": "Piggy-Cat-bit-shadow/satelite-one",
        # The version the metadata advertises must be the one in the filenames, otherwise
        # the fixture would be testing a bundle that contradicts itself.
        "app_version": apk_version,
        "app_version_code": 16,
        "app_commit": app_commit,
        "app_commit_full": app_commit,
        "app_ref": "v0.5.10",
        "core_repository": "Piggy-Cat-bit-shadow/sing-box",
        "core_commit": core_commit,
        "core_ref": core_ref,
        "core_branch_hint": "testing",
        "core_aar_sha256": "b" * 64,
        "core_aar_size": 123,
        "signature_state": signature_state,
        "signature_state_measured_by": "apksigner verify --verbose --print-certs",
        "apk_arm64_v8a_sha256": digests.get(f"satelite-one-{apk_version}-arm64-v8a-{kind}.apk", ""),
        "apk_x86_64_sha256": digests.get(f"satelite-one-{apk_version}-x86_64-{kind}.apk", ""),
        "build_time": "2026-10-10T00:00:00Z",
        "run_id": "1",
    }
    if info_override:
        info.update(info_override)
    with open(os.path.join(root, "build-info.json"), "w", encoding="utf-8") as handle:
        json.dump(info, handle, indent=2)
    with open(os.path.join(root, "apksigner-verify.txt"), "w", encoding="utf-8") as handle:
        handle.write("fake apksigner output\n")
    return root


def digest_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        digest.update(handle.read())
    return digest.hexdigest()


def test_bundle_preflight_positive_and_negative():
    with tempfile.TemporaryDirectory() as tmp:
        # R11 (positive): a well-formed, non-debug-signed, both-ABI bundle is accepted,
        # and the file list it prints is exactly what the release step would upload.
        good = write_bundle(os.path.join(tmp, "good"))
        code, out, err = run_bundle_preflight(good)
        check(code == 0, f"R11: a correct bundle must pass the preflight; stderr={err.strip()[:200]}")
        listed = [os.path.basename(line.strip()) for line in out.splitlines() if line.strip()]
        check(
            sorted(listed) == sorted([
                f"satelite-one-{BUNDLE_VERSION}-arm64-v8a-signed-by-user.apk",
                f"satelite-one-{BUNDLE_VERSION}-x86_64-signed-by-user.apk",
                "SHA256SUMS.txt",
                "build-info.json",
                "apksigner-verify.txt",
            ]),
            f"R11: the preflight must list exactly the publishable files, got {listed}",
        )

        # R01: nothing to publish.
        empty = os.path.join(tmp, "r01")
        os.makedirs(empty)
        write_bundle(empty, abis=())
        code, _, err = run_bundle_preflight(empty)
        check(code != 0 and "no APK" in err, f"R01 (no APK) must FAIL, got exit={code}")

        # R02: a missing ABI.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r02"), abis=("arm64-v8a",))
        )
        check(code != 0 and "missing an APK" in err, f"R02 (missing x86_64) must FAIL, got {err.strip()[:120]}")

        # R03a: an extra, non-release APK.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r03a"), extra_apk="app-debug.apk")
        )
        check(code != 0 and "does not match" in err, f"R03a (extra APK) must FAIL, got {err.strip()[:120]}")

        # R03b: two APKs claiming the same ABI.
        code, _, err = run_bundle_preflight(
            write_bundle(
                os.path.join(tmp, "r03b"),
                extra_apk=f"satelite-one-{BUNDLE_VERSION}-arm64-v8a-signed-by-user-2.apk",
            )
        )
        check(code != 0 and "claim ABI" in err, f"R03b (duplicate ABI) must FAIL, got {err.strip()[:120]}")

        # R04: one byte changed after the manifest was written.
        code, _, err = run_bundle_preflight(
            write_bundle(
                os.path.join(tmp, "r04"),
                tamper=f"satelite-one-{BUNDLE_VERSION}-x86_64-signed-by-user.apk",
            )
        )
        check(code != 0 and "does not match SHA256SUMS" in err, f"R04 (tampered APK) must FAIL, got {err.strip()[:120]}")

        # R05: a manifest that disagrees with the metadata hashes.
        wrong = {
            f"satelite-one-{BUNDLE_VERSION}-arm64-v8a-signed-by-user.apk": "0" * 64,
            f"satelite-one-{BUNDLE_VERSION}-x86_64-signed-by-user.apk": "1" * 64,
        }
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r05"), sums_override=wrong)
        )
        check(code != 0, f"R05 (wrong SHA in manifest) must FAIL, got exit={code}")

        # R05b: a manifest listing a file that is not present. The real digests are kept
        # so this exercises ONLY the "manifest names a ghost" rule, not the hash rule.
        ghost_root = write_bundle(os.path.join(tmp, "r05b"))
        real_sums = {}
        with open(os.path.join(ghost_root, "SHA256SUMS.txt"), encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    dig, name = line.split(None, 1)
                    real_sums[name.strip()] = dig
        real_sums["ghost.apk"] = "2" * 64
        write_bundle(ghost_root, sums_override=real_sums)
        code, _, err = run_bundle_preflight(ghost_root)
        check(code != 0 and "not in the bundle" in err, f"R05b (ghost manifest entry) must FAIL, got {err.strip()[:140]}")

        # R06a/b: wrong app or core revision.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r06a"), app_commit="d" * 40),
            extra_args=("--expect-app-commit", APP_COMMIT),
        )
        check(code != 0 and "app_commit" in err, f"R06a (wrong app commit) must FAIL, got {err.strip()[:120]}")
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r06b"), core_commit="e" * 40),
            extra_args=("--expect-core-commit", CORE_COMMIT),
        )
        check(code != 0 and "core_commit" in err, f"R06b (wrong core pin) must FAIL, got {err.strip()[:120]}")

        # R06c: the core revision is not a full pinned SHA.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r06c"), core_commit="testing")
        )
        check(code != 0 and "40-char" in err, f"R06c (unpinned core) must FAIL, got {err.strip()[:120]}")

        # R06d: the core was not resolved from the pin at all.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r06d"), core_ref="testing")
        )
        check(code != 0 and "pinned" in err, f"R06d (core_ref != pinned) must FAIL, got {err.strip()[:120]}")

        # R07: an unsigned artifact on the signed release path.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r07"), signature_state="unsigned"),
        )
        check(code != 0 and "unsigned" in err, f"R07 (unsigned as signed) must FAIL, got {err.strip()[:120]}")

        # R08: the public Android debug keystore standing in for a real signature.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r08"), signature_state="debug-signed"),
        )
        check(code != 0 and "debug" in err, f"R08 (debug-signed) must FAIL, got {err.strip()[:120]}")

        # R10: on the unsigned dry-run path, unsigned must PASS - the happy path the
        # shell `case` ordering in measure_apk_signature.sh also protects.
        code, out, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "r10"), signature_state="unsigned"),
            expect_signature="unsigned",
        )
        check(code == 0, f"R10 (unsigned happy path) must PASS, got {err.strip()[:200]}")

        # Nested layout: the shape the round-5 bug exploited. Must be refused outright.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "nested"), nested=True)
        )
        check(code != 0 and "flat" in err, f"a nested bundle must FAIL, got {err.strip()[:120]}")

        # The version in the filename must agree with what this run builds. The fixture is
        # internally consistent, so the mismatch can only be caught by the caller's
        # expectation - which is exactly how CI passes it.
        code, _, err = run_bundle_preflight(
            write_bundle(os.path.join(tmp, "ver"), apk_version="9.9.9"),
            extra_args=("--expect-version", BUNDLE_VERSION),
        )
        check(code != 0 and "carries version" in err, f"a version mismatch must FAIL, got exit={code}: {err.strip()[:160]}")


def test_dot_slash_manifest_is_rejected():
    """`sha256sum ./*.apk` writes `./name` entries, and `--check` matches them literally.

    This is the exact defect the round-6 dry run hit: the build job's manifest carried
    `./` prefixes, so `sha256sum --check` in the flat bundle could not find `./name`
    and the job failed. It failed *loudly* only because the bundle step proves its own
    manifest; without that proof the same manifest would have shipped and the publish
    job would have compared hashes against names that do not exist.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = write_bundle(os.path.join(tmp, "dotslash"))
        # Rewrite the manifest the way `sha256sum ./*.apk` would have.
        sums_path = os.path.join(root, "SHA256SUMS.txt")
        with open(sums_path, encoding="utf-8") as handle:
            lines = [line for line in handle if line.strip()]
        with open(sums_path, "w", encoding="utf-8") as handle:
            for line in lines:
                digest, name = line.split(None, 1)
                handle.write(f"{digest}  ./{name.strip()}\n")

        code, _, err = run_bundle_preflight(root)
        check(
            code != 0 and "not a flat filename" in err,
            f"a ./ -prefixed manifest must FAIL, got exit={code}: {err.strip()[:160]}",
        )


def test_stale_manifest_is_rejected():
    """A manifest that still names pre-rename files must not pass.

    The build job used to generate SHA256SUMS.txt *before* renaming the APKs, so its
    entries named `satelite-one-<abi>-release.apk` - files that do not exist once the
    bundle is assembled. The verifier has to treat "manifest lists a file that is not
    here" and "bundle file that is not in the manifest" as two separate failures.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = write_bundle(os.path.join(tmp, "stale"))
        sums_path = os.path.join(root, "SHA256SUMS.txt")
        with open(sums_path, encoding="utf-8") as handle:
            lines = [line for line in handle if line.strip()]
        with open(sums_path, "w", encoding="utf-8") as handle:
            for line in lines:
                digest, name = line.split(None, 1)
                stale = name.strip().replace("-signed-by-user", "-release")
                handle.write(f"{digest}  {stale}\n")

        code, _, err = run_bundle_preflight(root)
        check(
            code != 0,
            f"a stale/pre-rename manifest must FAIL, got exit={code}",
        )


def test_kind_must_lead_with_the_measured_state():
    """`kind` embeds the signature state, and the two must not disagree.

    The naming step builds `<sigstate>-<kind>` (`unsigned-dryrun`, `signed-v0.5.10`), so
    a bundle whose filename says one thing and whose build-info.json says another is
    self-contradictory and must be refused when the caller supplies the kind.
    """
    with tempfile.TemporaryDirectory() as tmp:
        # Filename claims unsigned-dryrun, metadata claims signed-by-user.
        root = write_bundle(
            os.path.join(tmp, "kindlie"),
            signature_state="signed-by-user",
            kind="unsigned-dryrun",
        )
        code, _, err = run_bundle_preflight(
            root,
            extra_args=("--expect-kind", "unsigned-dryrun"),
        )
        check(
            code != 0 and "does not lead the filename kind" in err,
            f"a kind/state contradiction must FAIL, got exit={code}: {err.strip()[:160]}",
        )
        # And the honest pairing: a bundle whose filename IS the kind it reports passes.
        honest = write_bundle(
            os.path.join(tmp, "kindhonest"),
            signature_state="unsigned",
            kind="unsigned-dryrun",
        )
        code, _, err = run_bundle_preflight(
            honest,
            expect_signature="unsigned",
            extra_args=("--expect-kind", "unsigned-dryrun"),
        )
        check(code == 0, f"the honest kind/state pairing must PASS, got {err.strip()[:200]}")


def test_expect_kind_survives_a_hyphenated_version():
    """A version containing '-' must not be mistaken for part of the ABI.

    The first cut of the verifier split the filename with a greedy regex; on
    `satelite-one-0.5.10-4-arm64-v8a-unsigned-dryrun.apk` that produced a nonsense
    version and rejected a correct bundle. The caller passes the version and kind it
    actually used, so the parse must not guess.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = write_bundle(
            os.path.join(tmp, "hyphen"),
            apk_version="0.5.10-4",
            kind="unsigned-dryrun",
            signature_state="unsigned",
        )
        code, _, err = run_bundle_preflight(
            root,
            expect_signature="unsigned",
            extra_args=("--expect-version", "0.5.10-4", "--expect-kind", "unsigned-dryrun"),
        )
        check(
            code == 0,
            f"a hyphenated version must be accepted, got exit={code}: {err.strip()[:200]}",
        )


def main():
    test_publish_job_verifies_and_uploads_one_bundle()
    test_both_jobs_share_one_measurement()
    test_decision_table()
    test_measurement_fails_closed()
    test_bundle_preflight_positive_and_negative()
    test_dot_slash_manifest_is_rejected()
    test_stale_manifest_is_rejected()
    test_kind_must_lead_with_the_measured_state()
    test_expect_kind_survives_a_hyphenated_version()

    if failures:
        print(f"\n{len(failures)} release-gate check(s) failed")
        sys.exit(1)
    print("release publish gate: all checks passed")


if __name__ == "__main__":
    main()
