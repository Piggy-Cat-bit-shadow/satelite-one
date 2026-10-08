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

Usage:
    check_core_provenance.py <libbox.aar> <libbox.provenance> <expected-sha>

Exit status is 0 only when every check passes.
"""
import os
import sys
import zipfile


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
    if len(sys.argv) != 4:
        fail("usage: check_core_provenance.py <libbox.aar> <libbox.provenance> <expected-sha>")
    aar_path, provenance_path, expected = sys.argv[1], sys.argv[2], sys.argv[3].strip()

    if not expected:
        fail("expected core revision is empty")

    fields = read_provenance(provenance_path)
    recorded_commit = fields.get("commit", "")
    recorded_version = fields.get("version", "")

    print(f"expected core revision : {expected}")
    print(f"provenance commit      : {recorded_commit}")
    print(f"provenance version     : {recorded_version}")

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

    if not aar_contains(aar_path, expected.encode("utf-8")):
        fail(
            f"{aar_path} does not contain the advertised core revision '{expected}'. "
            "The packaged binary was not built from the pinned revision."
        )

    size = os.path.getsize(aar_path)
    print(f"OK: libbox.aar ({size} bytes) carries core revision {expected}")


if __name__ == "__main__":
    main()
