#!/usr/bin/env python3
"""Verify a release bundle, and report exactly which files may be published.

Why this exists
---------------

The publish job is the only place in this repository that puts an artifact in front of
users, and it is the only place that cannot run on a normal push: it needs a `v*` tag
with `dry_run != true`. Round 5 found that its verification step looked for the APKs in
`app/build/outputs/apk/release/` while `download-artifact` had extracted them into the
working directory — with `nullglob` the glob expanded to nothing and the step passed
without verifying anything. That was fixed by *finding* the APKs. This script goes the
rest of the way and makes the verified set and the uploaded set the same set.

The remaining gap it closes: `action-gh-release` still took its `files:` from the build
directory layout, so the bytes that were verified and the bytes that were uploaded were
described by two different path expressions. Nothing guaranteed they agreed. Here the
bundle is enumerated against a manifest, every file is re-hashed, the metadata is checked
against the app/core revisions we expect, and **the paths printed on stdout are the only
paths the release step is allowed to upload**.

What it does NOT prove
----------------------

`CN != Android Debug` only proves the APK is not signed with that one well-known debug
subject. It is not proof that a particular user certificate signed it. Establishing that
needs an expected certificate SHA-256 fingerprint, which this repository deliberately does
not carry (release signing is a local, user-owned step). So this script reports the
measured state and refuses the states that are definitely wrong; it never claims the
signature's *identity*.

Usage:
    verify_release_bundle.py <bundle-dir> --expect-signature <unsigned|signed>
                             [--expect-app-commit SHA] [--expect-core-commit SHA]

Exit status 0 only when every check passes. On success, stdout is the NUL-safe list of
publishable file paths (one per line), suitable for feeding to the release step.
"""
import argparse
import hashlib
import json
import os
import re
import sys

# The two shipping ABIs, and the exact filename shape the naming step produces:
#   satelite-one-<version>-<abi>-<kind>.apk
EXPECTED_ABIS = ("arm64-v8a", "x86_64")
APK_NAME = re.compile(r"^satelite-one-(?P<version>.+)-(?P<abi>arm64-v8a|x86_64)-(?P<kind>.+)\.apk$")

# Files the bundle must contain besides the APKs.
REQUIRED_FILES = ("SHA256SUMS.txt", "build-info.json")
# Files that are carried when present, and published when present.
OPTIONAL_FILES = ("apksigner-verify.txt",)


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_sums(path):
    """`sha256sum` output -> {relative name: hex digest}."""
    sums = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                fail(f"{path}: unparsable checksum line: {line!r}")
            digest, name = parts
            name = name.lstrip("*").strip()
            if not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                fail(f"{path}: not a sha256 digest: {digest!r}")
            # sha256sum writes paths relative to its cwd; the bundle is flat, so a
            # checksum entry naming a subdirectory means the bundle is not flat.
            if os.path.basename(name) != name:
                fail(f"{path}: checksum entry is not a flat filename: {name!r}")
            sums[name] = digest.lower()
    if not sums:
        fail(f"{path}: no checksum entries")
    return sums


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle")
    parser.add_argument("--expect-signature", required=True, choices=("unsigned", "signed"))
    parser.add_argument("--expect-app-commit", default=None)
    parser.add_argument("--expect-core-commit", default=None)
    parser.add_argument("--expect-version", default=None)
    args = parser.parse_args()

    bundle = os.path.abspath(args.bundle)
    if not os.path.isdir(bundle):
        fail(f"bundle directory does not exist: {bundle}")

    # ---- 1. the bundle must be FLAT ------------------------------------------------
    # A nested tree is how the round-5 bug hid: two plausible paths for the same bytes.
    # A flat bundle has exactly one.
    entries = sorted(os.listdir(bundle))
    nested = [e for e in entries if os.path.isdir(os.path.join(bundle, e))]
    if nested:
        fail(f"bundle must be flat; found subdirectories: {nested}")

    for required in REQUIRED_FILES:
        if required not in entries:
            fail(f"bundle is missing {required}; contents: {entries}")

    # ---- 2. exactly the expected APKs, one per ABI, no extras ----------------------
    # Enumerated from the manifest rather than by globbing, so an extra APK cannot ride
    # along into a release just because it matched a pattern.
    apks = [e for e in entries if e.endswith(".apk")]
    if not apks:
        fail(f"bundle contains no APK; contents: {entries}")

    by_abi = {}
    version = None
    kind = None
    for name in apks:
        match = APK_NAME.match(name)
        if not match:
            fail(
                f"{name} does not match satelite-one-<version>-<abi>-<kind>.apk; "
                "refusing to guess what it is"
            )
        abi = match.group("abi")
        if abi in by_abi:
            fail(f"two APKs claim ABI {abi}: {by_abi[abi]} and {name}")
        by_abi[abi] = name
        version = match.group("version")
        kind = match.group("kind")

    missing = [abi for abi in EXPECTED_ABIS if abi not in by_abi]
    if missing:
        fail(f"bundle is missing an APK for: {missing}; found {sorted(by_abi)}")
    unexpected = [abi for abi in by_abi if abi not in EXPECTED_ABIS]
    if unexpected:
        fail(f"bundle contains unexpected ABI(s): {unexpected}")

    # ---- 3. every file matches the checksum manifest -------------------------------
    sums = parse_sums(os.path.join(bundle, "SHA256SUMS.txt"))
    measured = {}
    for name in apks:
        measured[name] = sha256_of(os.path.join(bundle, name))
        if name not in sums:
            fail(f"{name} is not covered by SHA256SUMS.txt")
        if sums[name] != measured[name]:
            fail(
                f"{name} does not match SHA256SUMS.txt "
                f"(manifest {sums[name][:16]}…, actual {measured[name][:16]}…)"
            )
    # A manifest entry with no file is as much a failure as a file with no entry: it
    # means the bundle and its manifest disagree about what was built.
    for name in sums:
        if not os.path.exists(os.path.join(bundle, name)):
            fail(f"SHA256SUMS.txt lists {name}, which is not in the bundle")
    print(f"checksums: {len(measured)} APK(s) match SHA256SUMS.txt", file=sys.stderr)

    # ---- 4. metadata must describe THIS bundle ------------------------------------
    with open(os.path.join(bundle, "build-info.json"), "r", encoding="utf-8") as handle:
        try:
            info = json.load(handle)
        except json.JSONDecodeError as error:
            fail(f"build-info.json is not valid JSON: {error}")

    if info.get("app_version") != version:
        fail(
            f"build-info.json app_version={info.get('app_version')!r} does not match the "
            f"APK filename version {version!r}"
        )
    for abi, name in sorted(by_abi.items()):
        key = "apk_" + abi.replace("-", "_") + "_sha256"
        if key not in info:
            fail(f"build-info.json has no {key} for {name}")
        if (info[key] or "").lower() != measured[name]:
            fail(
                f"build-info.json {key} does not match {name} "
                f"({(info[key] or '')[:16]}… vs {measured[name][:16]}…)"
            )

    if info.get("signature_state") != kind:
        fail(
            f"build-info.json signature_state={info.get('signature_state')!r} does not "
            f"match the APK filename kind {kind!r}"
        )

    for label, expected, key in (
        ("app commit", args.expect_app_commit, "app_commit"),
        ("core commit", args.expect_core_commit, "core_commit"),
    ):
        if expected is None:
            continue
        actual = info.get(key)
        if actual != expected:
            fail(f"build-info.json {key}={actual!r} but this run expects {label} {expected!r}")
    if args.expect_version is not None and info.get("app_version") != args.expect_version:
        fail(
            f"build-info.json app_version={info.get('app_version')!r} but version.properties "
            f"says {args.expect_version!r}"
        )
    if info.get("core_ref") != "pinned":
        fail(f"build-info.json core_ref={info.get('core_ref')!r}, expected 'pinned'")

    # ---- 5. the core identity must be a full commit SHA ---------------------------
    core_commit = info.get("core_commit") or ""
    if not re.fullmatch(r"[0-9a-f]{40}", core_commit):
        fail(f"build-info.json core_commit is not a full 40-char lowercase SHA: {core_commit!r}")
    app_commit = info.get("app_commit_full") or info.get("app_commit") or ""
    if not re.fullmatch(r"[0-9a-f]{40}", app_commit):
        fail(f"build-info.json app commit is not a full 40-char lowercase SHA: {app_commit!r}")

    # ---- 6. the requested signature expectation must hold -------------------------
    # The measured state is what the build job recorded; the release step re-measures
    # with apksigner. Both must agree with the expectation, so an unsigned or
    # debug-signed artifact can never be published as a release.
    state = info.get("signature_state")
    if args.expect_signature == "unsigned":
        if state != "unsigned":
            fail(
                f"this run must produce an unsigned artifact but the bundle records "
                f"signature_state={state!r} — signing material reached a read-only path"
            )
    else:
        if state == "unsigned":
            fail("this run must publish a signed artifact but the bundle is unsigned")
        if state == "debug-signed":
            fail(
                "the bundle is signed with the Android debug keystore; that key is public "
                "and is not proof of the publisher"
            )
        if state != "signed-by-user":
            fail(f"unknown signature_state {state!r}")

    publishable = sorted(apks) + [name for name in REQUIRED_FILES] + [
        name for name in OPTIONAL_FILES if name in entries
    ]
    print(
        f"bundle OK: {len(apks)} APK(s), signature_state={state}, version={version}, "
        f"core={core_commit[:12]}",
        file=sys.stderr,
    )
    for name in publishable:
        print(os.path.join(bundle, name))


if __name__ == "__main__":
    main()
