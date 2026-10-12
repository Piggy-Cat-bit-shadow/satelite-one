#!/usr/bin/env python3
"""Exercise resolve_libbox_aar.sh - including the cases that must FAIL.

Background: both CI workflows used to do

    cp libbox.aar "${GITHUB_WORKSPACE}/app/libs/libbox.aar"

while the current `cmd/internal/build_libbox` writes the AAR to an isolated gomobile
work directory (`<root>/_libbox_build/libbox.aar`). The release workflow was fixed
first; android-ci.yml kept the stale assumption, so the same defect existed twice. Both
now call one resolver, and this file is what keeps the resolver honest: a path rule that
is only ever pointed at a working checkout has never been shown to reject anything.

Covered:
  CASE 1  <root>/_libbox_build/libbox.aar exists            -> PASS, that file
  CASE 2  only the legacy <root>/libbox.aar exists          -> PASS, that file
  CASE 3  neither exists                                    -> FAIL
  CASE 3b both exist and DIFFER                             -> FAIL (ambiguous)
  CASE 3c both exist and are identical                      -> PASS (not ambiguous)
  CASE 3d root is not a directory / no argument             -> FAIL (usage)
  CASE 4  resolved AAR has no engine for a shipping ABI     -> the provenance gate FAILS it
  CASE 5  resolved AAR's provenance records another core    -> the provenance gate FAILS it
  CHAIN   resolver output -> provenance gate                -> PASS on an honest artifact

Run: python3 .github/scripts/test_resolve_libbox_aar.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RESOLVER = os.path.join(HERE, "resolve_libbox_aar.sh")
PROVENANCE_GATE = os.path.join(HERE, "check_core_provenance.py")

CORE_SHA = "a267e99e0bf1811dc975b013e2e8d7a15ab40d23"
OTHER_SHA = "1965bbf351710b86722838ae61a91b62c97d29de"
SHIPPING_ABIS = ("arm64-v8a", "x86_64")

failures = []


def check(ok, message):
    print(("  OK   " if ok else "  FAIL ") + message)
    if not ok:
        failures.append(message)
    return ok


def bash_path():
    """`bash` in CI; overridable so the suite can run where only Git's sh exists."""
    override = os.environ.get("RESOLVE_LIBBOX_BASH")
    if override:
        return override
    return shutil.which("bash") or "bash"


BASH = bash_path()


def run_resolver(root):
    proc = subprocess.run(
        [BASH, RESOLVER, root],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "").strip(), proc.stderr or ""


def run_provenance_gate(aar, provenance, expected):
    proc = subprocess.run(
        [sys.executable, PROVENANCE_GATE, aar, provenance, expected],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def make_aar(path, abis=SHIPPING_ABIS, payload=CORE_SHA.encode()):
    """A real zip, because the provenance gate opens the AAR and looks inside it."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", "<manifest/>")
        archive.writestr("classes.jar", b"PK\x03\x04")
        for abi in abis:
            # The gate insists each shipping ABI carries its own engine, and that the
            # packaged bytes contain the advertised revision.
            archive.writestr(f"jni/{abi}/libbox.so", b"\x7fELF" + payload + b"\x00" * 64)
    return path


def write_provenance(path, commit=CORE_SHA, version=CORE_SHA):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(f"commit={commit}\nversion={version}\ntags.android-main=x\n")
    return path


def case_1_primary_layout():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        expected = make_aar(os.path.join(root, "_libbox_build", "libbox.aar"))
        code, out, err = run_resolver(root)
        check(code == 0, "CASE 1 primary layout resolves (exit 0)")
        check(
            os.path.normcase(os.path.abspath(out)) == os.path.normcase(os.path.abspath(expected)),
            "CASE 1 resolves to <root>/_libbox_build/libbox.aar",
        )
        check("_libbox_build" in out, "CASE 1 path is the isolated work directory")


def case_2_legacy_layout():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        expected = make_aar(os.path.join(root, "libbox.aar"))
        code, out, err = run_resolver(root)
        check(code == 0, "CASE 2 legacy module-root layout still resolves (exit 0)")
        check(
            os.path.normcase(os.path.abspath(out)) == os.path.normcase(os.path.abspath(expected)),
            "CASE 2 resolves to <root>/libbox.aar",
        )


def case_3_missing():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        os.makedirs(root)
        code, out, err = run_resolver(root)
        check(code != 0, "CASE 3 no AAR anywhere FAILS")
        check(out == "", "CASE 3 prints no path on failure (nothing to `cp`)")
        check("produced no libbox.aar" in err, "CASE 3 explains that no AAR was produced")
        check("_libbox_build" in err, "CASE 3 names the directory it looked in")


def case_3b_ambiguous():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        make_aar(os.path.join(root, "_libbox_build", "libbox.aar"), payload=CORE_SHA.encode())
        make_aar(os.path.join(root, "libbox.aar"), payload=OTHER_SHA.encode())
        code, out, err = run_resolver(root)
        check(code != 0, "CASE 3b two DIFFERENT candidates FAIL (ambiguous, not a preference)")
        check(out == "", "CASE 3b prints no path when ambiguous")
        check("two different libbox.aar candidates" in err, "CASE 3b says why it refused")


def case_3c_identical():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        make_aar(os.path.join(root, "_libbox_build", "libbox.aar"))
        shutil.copyfile(
            os.path.join(root, "_libbox_build", "libbox.aar"),
            os.path.join(root, "libbox.aar"),
        )
        code, out, err = run_resolver(root)
        check(code == 0, "CASE 3c identical bytes in both locations is NOT ambiguous")
        check("_libbox_build" in out, "CASE 3c prefers the current location")


def case_3d_usage():
    code, out, err = run_resolver("")
    check(code != 0, "CASE 3d missing argument FAILS")
    with tempfile.TemporaryDirectory() as tmp:
        code, out, err = run_resolver(os.path.join(tmp, "does-not-exist"))
        check(code != 0, "CASE 3d non-existent checkout root FAILS")
        check("not a directory" in err, "CASE 3d says the root is not a directory")


def case_4_missing_engine():
    """The resolver must not launder an AAR past the gate that follows it."""
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        make_aar(os.path.join(root, "_libbox_build", "libbox.aar"), abis=("arm64-v8a",))
        provenance = write_provenance(os.path.join(root, "libbox.provenance"))
        code, out, err = run_resolver(root)
        check(code == 0, "CASE 4 resolver itself accepts the file it found")
        gate_code, gate_out = run_provenance_gate(out, provenance, CORE_SHA)
        check(gate_code != 0, "CASE 4 an AAR with no x86_64 engine FAILS the provenance gate")
        check("x86_64" in gate_out, "CASE 4 the gate names the missing ABI")


def case_5_wrong_provenance():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        make_aar(os.path.join(root, "_libbox_build", "libbox.aar"))
        provenance = write_provenance(os.path.join(root, "libbox.provenance"), commit=OTHER_SHA)
        code, out, err = run_resolver(root)
        check(code == 0, "CASE 5 resolver itself accepts the file it found")
        gate_code, gate_out = run_provenance_gate(out, provenance, CORE_SHA)
        check(gate_code != 0, "CASE 5 provenance naming another core FAILS the gate")
        check("mismatch" in gate_out, "CASE 5 the gate reports a provenance mismatch")


def case_chain_honest():
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "sing-box")
        make_aar(os.path.join(root, "_libbox_build", "libbox.aar"))
        provenance = write_provenance(os.path.join(root, "libbox.provenance"))
        code, out, err = run_resolver(root)
        check(code == 0, "CHAIN resolver accepts the honest AAR")
        gate_code, gate_out = run_provenance_gate(out, provenance, CORE_SHA)
        check(gate_code == 0, "CHAIN resolved AAR PASSES the provenance gate")


def main():
    if not os.path.isfile(RESOLVER):
        print(f"::error::missing {RESOLVER}", file=sys.stderr)
        return 1
    print(f"resolver : {RESOLVER}")
    print(f"bash     : {BASH}")
    print()
    case_1_primary_layout()
    case_2_legacy_layout()
    case_3_missing()
    case_3b_ambiguous()
    case_3c_identical()
    case_3d_usage()
    case_4_missing_engine()
    case_5_wrong_provenance()
    case_chain_honest()
    print()
    if failures:
        for message in failures:
            print(f"::error::{message}", file=sys.stderr)
        print(f"{len(failures)} check(s) FAILED", file=sys.stderr)
        return 1
    print("OK: the libbox.aar resolver accepts both real layouts and refuses the rest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
