#!/usr/bin/env python3
"""Negative tests for the release ABI/engine guard.

The guard exists because this repository shipped the defect it now rejects: an arm64-v8a
APK that assembled, installed and passed every filename, checksum, metadata and
provenance check while containing no engine at all. The AAR it was built from packaged
only x86_64, and no gate ever opened the APK.

A gate that has never been seen to fail is not evidence, so every case below is an
artifact shape that must be REJECTED, built as a real bundle on disk and run through the
real `verify_release_bundle.py`. The positive case is included for the same reason: a
guard that rejects everything would pass all the negatives and prove nothing.

Pure stdlib, no network, no Android; runs in about a second.

Usage:  test_release_abi_guard.py
Exit 0 when every case behaves as required.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
GATE = os.path.join(REPO, ".github", "scripts", "verify_release_bundle.py")

VERSION = "0.5.10"
KIND = "unsigned-dryrun"
CORE = "c" * 40
APP = "a" * 40
ENGINE = b"\x7fELF" + bytes(range(256)) * 8
APK_NAME = re.compile(r"^satelite-one-(?P<version>.+)-(?P<abi>arm64-v8a|x86_64)-(?P<kind>.+)\.apk$")

failures: list[str] = []


def fail_case(label, detail):
    failures.append(f"{label}: {detail}")


def sha256(payload):
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def make_aar(path, engines):
    """An AAR holding `engines` = {abi: bytes} at jni/<abi>/libbox.so."""
    with zipfile.ZipFile(path, "w") as archive:
        for abi, payload in engines.items():
            archive.writestr(f"jni/{abi}/libbox.so", payload)


def make_apk(path, engines, extra_entries=None):
    """An APK holding `engines` = {abi: bytes} at lib/<abi>/libbox.so."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("classes.dex", b"dex")
        for abi, payload in engines.items():
            archive.writestr(f"lib/{abi}/libbox.so", payload)
        for name, payload in (extra_entries or {}).items():
            archive.writestr(name, payload)


def read_entries(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def reseal(path, entries):
    """Rewrite an APK archive from `entries`, exactly and only those entries.

    Not routed through `make_apk`: that adds the manifest/dex itself, which would write
    duplicate entries and leave the archive subtly different from the one just read.
    """
    with zipfile.ZipFile(path, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def refresh_whole_file_checksums(root):
    """Recompute SHA256SUMS.txt and the per-APK build-info digests.

    The engine cases must fail because of what is INSIDE the APK, not because the
    whole-file manifest went stale while the archive was resealed. Keeping the
    outer hashes honest is what makes each rejection attributable to the engine check.
    """
    names = sorted(n for n in os.listdir(root) if n.endswith(".apk"))
    with open(os.path.join(root, "SHA256SUMS.txt"), "w", encoding="utf-8") as handle:
        for name in names:
            handle.write(f"{sha256_file(os.path.join(root, name))}  {name}\n")
    info_path = os.path.join(root, "build-info.json")
    with open(info_path, encoding="utf-8") as handle:
        info = json.load(handle)
    for name in names:
        abi = APK_NAME.match(name).group("abi")
        info["apk_" + abi.replace("-", "_") + "_sha256"] = sha256_file(
            os.path.join(root, name)
        )
    with open(info_path, "w", encoding="utf-8") as handle:
        json.dump(info, handle, indent=2)


def write_bundle(root, aar_engines, apk_engines, *, engine_map=None, omit_map=False):
    """A release bundle: one APK per ABI in `apk_engines`, plus manifest and metadata.

    `apk_engines` maps abi -> {abi: bytes}, i.e. it lets a case give the arm64 APK an
    x86_64-only payload, which is the wrong-ABI case (N2).

    `engine_map` defaults to the HONEST map derived from `aar_engines`, deliberately.
    A rejection that came from a wrong map would not prove the APK was inspected, and
    N2 in particular must fail because of what is inside the APK.

    The AAR is written BESIDE the bundle, not into it: the bundle must be flat and must
    contain nothing the verifier does not recognise, and in CI the AAR lives at
    `app/libs/libbox.aar` while the bundle is a separate directory. Putting it inside
    would make every case fail on "unexpected file" instead of on the engine.
    """
    bundle = os.path.join(root, "release-bundle")
    os.makedirs(bundle, exist_ok=True)
    aar_path = os.path.join(root, "libbox.aar")
    make_aar(aar_path, aar_engines)

    names = {}
    for abi, payloads in apk_engines.items():
        name = f"satelite-one-{VERSION}-{abi}-{KIND}.apk"
        make_apk(os.path.join(bundle, name), payloads)
        names[abi] = name

    if engine_map is None:
        engine_map = {abi: sha256(payload) for abi, payload in aar_engines.items()}

    with open(os.path.join(bundle, "SHA256SUMS.txt"), "w", encoding="utf-8") as handle:
        for name in sorted(names.values()):
            handle.write(f"{sha256_file(os.path.join(bundle, name))}  {name}\n")

    info = {
        "app_version": VERSION,
        "core_commit": CORE,
        "app_commit": APP,
        "app_commit_full": APP,
        "core_ref": "pinned",
        "signature_state": "unsigned",
        "core_engine_sha256": {} if omit_map else engine_map,
    }
    for abi, name in names.items():
        info["apk_" + abi.replace("-", "_") + "_sha256"] = sha256_file(
            os.path.join(bundle, name)
        )
    with open(os.path.join(bundle, "build-info.json"), "w", encoding="utf-8") as handle:
        json.dump(info, handle, indent=2)
    return bundle, aar_path


def run_gate(bundle, aar=None, require_aar=False, extra=()):
    cmd = [
        sys.executable,
        GATE,
        str(bundle),
        "--expect-signature",
        "unsigned",
        "--expect-version",
        VERSION,
        "--expect-kind",
        KIND,
        "--expect-core-commit",
        CORE,
        *extra,
    ]
    if aar:
        cmd += ["--aar", str(aar)]
    if require_aar:
        cmd += ["--require-aar"]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def expect_reject(label, bundle, aar=None, **kwargs):
    code, _, err = run_gate(bundle, aar, **kwargs)
    if code == 0:
        fail_case(label, "gate ACCEPTED an artifact it must reject")
    else:
        print(f"  rejected as required: {label}")


def expect_accept(label, bundle, aar=None, **kwargs):
    code, _, err = run_gate(bundle, aar, **kwargs)
    if code != 0:
        fail_case(label, f"gate REJECTED a valid artifact: {err.strip()[:300]}")
    else:
        print(f"  accepted as required: {label}")


def main() -> int:
    both = {"arm64-v8a": ENGINE, "x86_64": ENGINE + b"-x86"}
    with tempfile.TemporaryDirectory() as tmp:
        # ---- N6 (the control): the correct shape must still PASS ---------------
        # Without this, a guard that rejected everything would satisfy every case below.
        good, good_aar = write_bundle(
            os.path.join(tmp, "good"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        expect_accept("N6 correct dual-ABI AAR + both split APKs", good, good_aar, require_aar=True)

        # ---- N1: the arm64 APK has no lib/ at all -----------------------------
        # The historical accident, exactly: assemble succeeds, install succeeds, and the
        # engine is simply absent.
        n1, n1_aar = write_bundle(
            os.path.join(tmp, "n1"),
            both,
            {"arm64-v8a": {}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        expect_reject("N1 arm64 APK carries no engine at all", n1, n1_aar)

        # ---- N2: the arm64 APK carries only the x86_64 engine ------------------
        # "A libbox.so exists somewhere" is not proof that THIS APK has its own core.
        n2, n2_aar = write_bundle(
            os.path.join(tmp, "n2"),
            both,
            {"arm64-v8a": {"x86_64": ENGINE + b"-x86"}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        expect_reject("N2 arm64 APK carries only the x86_64 engine", n2, n2_aar)

        # ---- N3: the x86_64 APK has no x86_64 engine ---------------------------
        n3, n3_aar = write_bundle(
            os.path.join(tmp, "n3"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {}},
        )
        expect_reject("N3 x86_64 APK carries no x86_64 engine", n3, n3_aar)

        # ---- N4: one byte of the packaged engine is modified -------------------
        # The APK's whole-file checksum is recomputed afterwards, so this is not the
        # stale-manifest case: the artifact is internally consistent and the ENGINE is
        # wrong. Only a digest taken over the engine itself can see it.
        n4, n4_aar = write_bundle(
            os.path.join(tmp, "n4"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        target = os.path.join(n4, f"satelite-one-{VERSION}-arm64-v8a-{KIND}.apk")
        entries = read_entries(target)
        payload = bytearray(entries["lib/arm64-v8a/libbox.so"])
        payload[16] ^= 0x01
        entries["lib/arm64-v8a/libbox.so"] = bytes(payload)
        reseal(target, entries)
        refresh_whole_file_checksums(n4)
        expect_reject("N4 packaged engine modified by one byte", n4, n4_aar)

        # ---- N5: a single-ABI AAR while the bundle ships two APKs ---------------
        # The abstract form of the historical accident. The arm64 APK is well-formed and
        # carries an arm64 engine, but that engine is not in the AAR the build used - so
        # the AAR could not have produced it. Requires --aar to detect, which is why CI
        # sets --require-aar.
        n5, n5_aar = write_bundle(
            os.path.join(tmp, "n5"),
            {"x86_64": ENGINE + b"-x86"},
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
            # the map claims the honest values, so the AAR comparison is what must fail
            engine_map={"arm64-v8a": sha256(ENGINE), "x86_64": sha256(ENGINE + b"-x86")},
        )
        expect_reject("N5 single-ABI AAR cannot account for the arm64 APK", n5, n5_aar)

        # ---- N5b: same shape, but the metadata was forged to match --------------
        # The digest map agrees with the APK while the AAR does not. Only the AAR-side
        # comparison catches this, and it is the reason the map alone is not enough.
        n5b, n5b_aar = write_bundle(
            os.path.join(tmp, "n5b"),
            {"x86_64": ENGINE + b"-x86"},
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
            engine_map={"arm64-v8a": sha256(ENGINE), "x86_64": sha256(ENGINE + b"-x86")},
        )
        expect_reject("N5b AAR disagrees with a self-consistent digest map", n5b, n5b_aar)

        # ---- N7: no digest map at all -----------------------------------------
        # "Cannot prove it" must never read as "nothing to prove".
        n7, n7_aar = write_bundle(
            os.path.join(tmp, "n7"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
            omit_map=True,
        )
        expect_reject("N7 build-info.json carries no engine digest map", n7, n7_aar)

        # ---- N8: an APK smuggling a second ABI's engine ------------------------
        n8, n8_aar = write_bundle(
            os.path.join(tmp, "n8"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        name = f"satelite-one-{VERSION}-arm64-v8a-{KIND}.apk"
        path = os.path.join(n8, name)
        entries = read_entries(path)
        entries["lib/x86_64/libbox.so"] = ENGINE + b"-x86"
        reseal(path, entries)
        refresh_whole_file_checksums(n8)
        expect_reject("N8 arm64 APK also packages the x86_64 engine", n8, n8_aar)

        # ---- N9: --require-aar without --aar -----------------------------------
        # The guard's strongest half must not be droppable by forgetting a flag.
        n9, _ = write_bundle(
            os.path.join(tmp, "n9"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        expect_reject("N9 --require-aar without --aar", n9, None, require_aar=True)

        # ---- N10: the honest bundle WITHOUT --aar must still pass --------------
        # The publish job has no AAR (it is not carried in the bundle), so the map-only
        # path must remain a real check rather than a hard failure.
        n10, _ = write_bundle(
            os.path.join(tmp, "n10"),
            both,
            {"arm64-v8a": {"arm64-v8a": ENGINE}, "x86_64": {"x86_64": ENGINE + b"-x86"}},
        )
        expect_accept("N10 honest bundle, no AAR available (publish-job shape)", n10)
        # ...and the publish-job shape must still reject a missing engine.
        expect_reject(
            "N10b no-AAR path still rejects a missing engine",
            n7,
        )

    if failures:
        print("\nFAILED:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    print("\nOK: the release ABI guard rejects every engine-absence and engine-mismatch case")
    return 0


if __name__ == "__main__":
    sys.exit(main())
