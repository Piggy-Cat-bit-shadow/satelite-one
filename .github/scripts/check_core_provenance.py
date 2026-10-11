#!/usr/bin/env python3
"""Assert that app/libs/libbox.aar really was built from the advertised core revision.

This is the CI twin of the Gradle `verifyCoreProvenance` task. It exists because
the app once advertised a core commit it did not contain: CORE_COMMIT was derived
from the sing-box checkout at APK-build time while constant.Version was baked in
at libbox-build time, so advancing the checkout in between silently mislabelled
the APK.

Two independent facts are checked:

  1. app/libs/libbox.provenance (written by build_libbox) records the revision the
     core build used.
  2. The packaged libbox.so actually contains that revision as a string.

Both must equal the revision this build intends to ship.

A third fact was added after this gate was found to be satisfiable by an AAR that could
not build the APKs it was supposed to: EVERY ABI this app ships must have its own
`jni/<abi>/libbox.so`. The original rule asked "does any packaged libbox.so contain the
pin?", which answers YES for an x86_64-only AAR - and the arm64-v8a split still
assembles, installs and ships with no engine at all. Revision identity was never the
same question as per-ABI engine presence, and conflating them is how an engine-less
APK passed every gate.

Usage:
    check_core_provenance.py <libbox.aar> <libbox.provenance> <expected-sha>
                             [--abis arm64-v8a,x86_64]

Exit status is 0 only when every check passes.
"""
import argparse
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _zipcontent import aar_engine_abis, aar_engine_digest  # noqa: E402

# The ABIs this app produces APK splits for, from app/build.gradle.kts
# (`splits { abi { include("arm64-v8a", "x86_64") } }`). Overridable for a test or a
# future ABI list; the release workflow passes nothing and gets exactly these.
DEFAULT_ABIS = ("arm64-v8a", "x86_64")


def fail(message: str) -> "None":
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def read_provenance(path: str) -> dict:
    if not os.path.isfile(path):
        fail(f"missing core provenance file: {path}")
    fields = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or "=" not in line:
                continue
            key, value = line.split("=", 1)
            fields[key.strip()] = value.strip()
    return fields


def aar_contains(aar_path: str, needle: bytes) -> bool:
    """True when any packaged libbox.so contains `needle`."""
    if not os.path.isfile(aar_path):
        fail(f"missing AAR: {aar_path}")
    with zipfile.ZipFile(aar_path) as archive:
        targets = [n for n in archive.namelist() if n.endswith("libbox.so")]
        if not targets:
            fail(f"{aar_path} contains no libbox.so")
        for name in targets:
            with archive.open(name) as handle:
                overlap = b""
                while True:
                    chunk = handle.read(1 << 20)
                    if not chunk:
                        break
                    window = overlap + chunk
                    if needle in window:
                        return True
                    overlap = window[-(len(needle) - 1):] if len(needle) > 1 else b""
    return False


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Prove the AAR carries the advertised core revision, for every shipping ABI.",
    )
    parser.add_argument("aar")
    parser.add_argument("provenance")
    parser.add_argument("expected", help="the full 40-char core commit this build ships")
    parser.add_argument(
        "--abis",
        default=",".join(DEFAULT_ABIS),
        help="comma-separated ABIs that must each carry their own engine "
        f"(default: {','.join(DEFAULT_ABIS)})",
    )
    args = parser.parse_args()

    aar_path, provenance_path, expected = args.aar, args.provenance, args.expected.strip()
    abis = tuple(a.strip() for a in args.abis.split(",") if a.strip())

    if not expected:
        fail("expected core revision is empty")
    if not abis:
        fail("no ABIs to check; a gate that checks nothing must not report success")

    fields = read_provenance(provenance_path)
    recorded_commit = fields.get("commit", "")
    recorded_version = fields.get("version", "")

    print(f"expected core revision : {expected}")
    print(f"provenance commit      : {recorded_commit}")
    print(f"provenance version     : {recorded_version}")
    print(f"shipping ABIs          : {', '.join(abis)}")

    if recorded_commit != expected:
        fail(
            "core provenance mismatch: provenance records commit "
            f"'{recorded_commit}' but this build ships '{expected}'"
        )

    # The version string is what Libbox.version() reports. CI sets it to the same
    # SHA, so requiring equality keeps "runtime version" and "core revision" from
    # ever disagreeing in a released artifact.
    if recorded_version != expected:
        fail(
            "core provenance version mismatch: provenance records version "
            f"'{recorded_version}' but this build ships '{expected}'. Build the core "
            "with SING_BOX_BUILD_VERSION=$CORE_SHA."
        )

    # Every shipping ABI must have its own engine. This is checked before the pin search
    # so a single-ABI AAR is reported as "the arm64 engine is missing" rather than as an
    # unexplained pin failure - the failure mode this rule exists for.
    packaged = aar_engine_abis(aar_path)
    missing_abis = [abi for abi in abis if abi not in packaged]
    if missing_abis:
        fail(
            f"{aar_path} packages no engine for {missing_abis}; it has {packaged}. "
            "Every ABI this app ships must have its own jni/<abi>/libbox.so - a split APK "
            "whose engine the AAR never provided still assembles and installs with no core."
        )
    for abi in abis:
        digest = aar_engine_digest(aar_path, abi)
        print(f"  jni/{abi}/libbox.so : sha256 {digest}")

    if not aar_contains(aar_path, expected.encode("utf-8")):
        fail(
            f"{aar_path} does not contain the advertised core revision '{expected}'. "
            "The packaged binary was not built from the pinned revision."
        )

    size = os.path.getsize(aar_path)
    print(f"OK: libbox.aar ({size} bytes) carries core revision {expected} for {', '.join(abis)}")


if __name__ == "__main__":
    main()
