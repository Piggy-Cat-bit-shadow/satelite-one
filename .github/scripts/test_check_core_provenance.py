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


def make_aar(path: str, embedded: bytes, abis=("arm64-v8a", "x86_64")) -> None:
    """A minimal AAR-shaped zip holding one libbox.so per ABI.

    Two ABIs by default because that is what the gate now requires: an AAR that packages
    only one of them cannot produce the other split with an engine, which is the defect
    this fixture must be able to express (see the single-ABI case below).
    """
    with zipfile.ZipFile(path, "w") as archive:
        for abi in abis:
            archive.writestr(f"jni/{abi}/libbox.so", b"\x7fELF" + embedded + b"\x00" * 64)


def make_provenance(path: str, commit: str, version: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(f"commit={commit}\nversion={version}\n")


def run(aar: str, provenance: str, expected: str, abis: str = None) -> int:
    cmd = [sys.executable, GATE, aar, provenance, expected]
    if abis is not None:
        cmd += ["--abis", abis]
    return subprocess.run(cmd, capture_output=True, text=True).returncode


def expect_reject(label: str, aar: str, provenance: str, expected: str, abis: str = None) -> None:
    rc = run(aar, provenance, expected, abis)
    if rc == 0:
        failures.append(f"{label}: gate ACCEPTED what it must reject")
    else:
        print(f"  rejected as required: {label}")


def expect_accept(label: str, aar: str, provenance: str, expected: str, abis: str = None) -> None:
    rc = run(aar, provenance, expected, abis)
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

        # 8. The gate used to pass an AAR that packaged only ONE of the two shipping
        #    ABIs: it asked "does any libbox.so contain the pin?", and x86_64 alone
        #    answered yes. The arm64-v8a split then assembled, installed and shipped
        #    with no engine, while this gate reported success. The shape below is that
        #    accident, reduced to its essentials.
        single_abi_aar = os.path.join(tmp, "x86_64-only.aar")
        make_aar(single_abi_aar, SHA_A.encode(), abis=("x86_64",))
        expect_reject(
            "AAR packaging only x86_64 (arm64 split would ship no engine)",
            single_abi_aar,
            good_prov,
            SHA_A,
        )

        # 9. ...and the same AAR asked only for the ABI it does have is a legitimate
        #    build for some other product, so the rule has to be "every ABI we were told
        #    to ship", not "every ABI imaginable". This keeps the check above honest: it
        #    fails because arm64-v8a is REQUIRED, not because the AAR has one ABI.
        expect_accept(
            "single-ABI AAR when only that ABI is required",
            single_abi_aar,
            good_prov,
            SHA_A,
            abis="x86_64",
        )

        # 10. An ABI list that is empty checks nothing, so it must be a configuration
        #     error rather than a vacuous pass.
        expect_reject("empty ABI list", good_aar, good_prov, SHA_A, abis=",")

    if failures:
        print("\nFAILED:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print("\nOK: the provenance gate rejects every mismatch case")
    return 0


if __name__ == "__main__":
    sys.exit(main())
