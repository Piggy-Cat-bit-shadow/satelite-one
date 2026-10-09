#!/usr/bin/env python3
"""Negative tests for the core-provenance gate.

The gate is what stops a released APK from advertising a core revision it does not
contain. A gate nobody ever sees fail is not evidence, so this exercises the cases
that must be REJECTED, including the one a cache is most likely to produce: an AAR
built from a different revision than the build intends to ship.

Pure stdlib, no network, no Android; runs in about a second.

Usage:  test_check_core_provenance.py
Exit 0 when every case behaves as required.
"""
import os
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.join(HERE, "check_core_provenance.py")

SHA_A = "a" * 40
SHA_B = "b" * 40

failures: list[str] = []


def make_aar(path: str, embedded: bytes) -> None:
    """A minimal AAR-shaped zip holding one libbox.so."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("jni/arm64-v8a/libbox.so", b"\x7fELF" + embedded + b"\x00" * 64)


def make_provenance(path: str, commit: str, version: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(f"commit={commit}\nversion={version}\n")


def run(aar: str, provenance: str, expected: str) -> int:
    return subprocess.run(
        [sys.executable, GATE, aar, provenance, expected],
        capture_output=True,
        text=True,
    ).returncode


def expect_reject(label: str, aar: str, provenance: str, expected: str) -> None:
    rc = run(aar, provenance, expected)
    if rc == 0:
        failures.append(f"{label}: gate ACCEPTED what it must reject")
    else:
        print(f"  rejected as required: {label}")


def expect_accept(label: str, aar: str, provenance: str, expected: str) -> None:
    rc = run(aar, provenance, expected)
    if rc != 0:
        failures.append(f"{label}: gate REJECTED a valid pair")
    else:
        print(f"  accepted as required: {label}")


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        good_aar = os.path.join(tmp, "good.aar")
        good_prov = os.path.join(tmp, "good.provenance")
        make_aar(good_aar, SHA_A.encode())
        make_provenance(good_prov, SHA_A, SHA_A)

        # The control: the matching pair must still pass, or the rejects below prove
        # nothing about the gate.
        expect_accept("matching AAR + provenance + expected", good_aar, good_prov, SHA_A)

        # 1. Stale cache / AAR from another revision: this is the case a cache key
        #    collision or a mis-pinned rebuild produces.
        expect_reject("AAR built from A checked against expected B", good_aar, good_prov, SHA_B)

        # 2. Provenance says one thing, the packaged binary another.
        swapped_prov = os.path.join(tmp, "swapped.provenance")
        make_provenance(swapped_prov, SHA_B, SHA_B)
        expect_reject("provenance relabelled to B over an A binary", good_aar, swapped_prov, SHA_B)

        # 3. Provenance version disagrees (the runtime Libbox.version() string).
        version_only_prov = os.path.join(tmp, "version.provenance")
        make_provenance(version_only_prov, SHA_A, SHA_B)
        expect_reject("commit matches but version disagrees", good_aar, version_only_prov, SHA_A)

        # 4. The advertised revision is not actually inside the binary.
        empty_aar = os.path.join(tmp, "empty.aar")
        make_aar(empty_aar, b"")
        expect_reject("binary does not contain the advertised revision", empty_aar, good_prov, SHA_A)

        # 5. Missing provenance / missing AAR must fail, not default to "unknown".
        expect_reject("missing provenance file", good_aar, os.path.join(tmp, "nope"), SHA_A)
        expect_reject("missing AAR", os.path.join(tmp, "nope.aar"), good_prov, SHA_A)

        # 6. No expected revision at all is a configuration error, never a pass.
        expect_reject("empty expected revision", good_aar, good_prov, "")

        # 7. An AAR with no libbox.so at all.
        bare_aar = os.path.join(tmp, "bare.aar")
        with zipfile.ZipFile(bare_aar, "w") as archive:
            archive.writestr("classes.jar", b"")
        expect_reject("AAR without libbox.so", bare_aar, good_prov, SHA_A)

    if failures:
        print("\nFAILED:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print("\nOK: the provenance gate rejects every mismatch case")
    return 0


if __name__ == "__main__":
    sys.exit(main())
