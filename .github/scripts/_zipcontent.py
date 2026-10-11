#!/usr/bin/env python3
"""Read the core engine out of an AAR or an APK, by ABI, with its digest.

Why this is its own module
--------------------------

Two gates need the same primitive and must not disagree about it:

  * `check_core_provenance.py` reads `jni/<abi>/libbox.so` out of the AAR, to prove
    the pinned revision is really inside the engine this build intends to ship.
  * `verify_release_bundle.py` reads `lib/<abi>/libbox.so` out of each release APK, to
    prove the APK carries ITS OWN ABI's engine and that those bytes are the AAR's.

The distinction the second one exists for is worth stating plainly, because the
repository has shipped the defect it guards against: an arm64-v8a APK can assemble,
install and pass every metadata check while containing no `lib/` directory at all, and
`check_core_provenance.py`'s older "does ANY packaged libbox.so contain the pin?"
question answers YES for that pipeline as long as some other ABI is present in the AAR.
Artifact identity and revision identity are different questions and need different
evidence: this module supplies the bytes-and-digest half of both.

Nothing here raises on a semantically wrong artifact. A missing engine is a *result*
(`None`), because the caller is the thing that knows whether missing is fatal, and it
must be able to say so with a message naming the ABI it wanted. Only I/O and
malformed-archive errors propagate.
"""
import hashlib
import os
import re
import zipfile

# The container each artifact keeps its engines in: an AAR is a zip with `jni/<abi>/`,
# an APK is a zip with `lib/<abi>/`. Both hold `libbox.so`.
AAR_ROOT = "jni"
APK_ROOT = "lib"
ENGINE_SONAME = "libbox.so"

# ABI directory names this pipeline is willing to reason about. Deliberately narrow:
# a name outside this set means the artifact layout is not one this repository builds,
# which is a configuration error rather than something to interpret.
ABI_NAME = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")


def _entry_name(root: str, abi: str) -> str:
    if not ABI_NAME.match(abi or ""):
        raise ValueError(f"not a usable ABI directory name: {abi!r}")
    return f"{root}/{abi}/{ENGINE_SONAME}"


def sha256_of(zip_path: str, entry_name: str):
    """SHA256 of one entry, streamed; None when the entry is absent."""
    with zipfile.ZipFile(zip_path) as archive:
        try:
            info = archive.getinfo(entry_name)
        except KeyError:
            return None
        digest = hashlib.sha256()
        with archive.open(info) as handle:
            while True:
                chunk = handle.read(1 << 20)
                if not chunk:
                    break
                digest.update(chunk)
        return digest.hexdigest()


def aar_engine_digest(aar_path: str, abi: str):
    """`jni/<abi>/libbox.so` digest in an AAR, or None when that ABI is absent."""
    return sha256_of(aar_path, _entry_name(AAR_ROOT, abi))


def apk_engine_digest(apk_path: str, abi: str):
    """`lib/<abi>/libbox.so` digest in an APK, or None when that ABI is absent."""
    return sha256_of(apk_path, _entry_name(APK_ROOT, abi))


def aar_engine_abis(aar_path: str):
    """Every ABI an AAR actually packages an engine for, sorted.

    Sorted rather than in archive order so a message built from it is stable across
    rebuilds; the caller compares it against what the bundle claims to ship.
    """
    found = set()
    with zipfile.ZipFile(aar_path) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == AAR_ROOT and parts[2] == ENGINE_SONAME:
                if parts[1]:
                    found.add(parts[1])
    return sorted(found)


def apk_engine_abis(apk_path: str):
    """Every ABI an APK actually packages an engine for, sorted."""
    found = set()
    with zipfile.ZipFile(apk_path) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == APK_ROOT and parts[2] == ENGINE_SONAME:
                if parts[1]:
                    found.add(parts[1])
    return sorted(found)


def aar_engine_map(aar_path: str) -> dict:
    """{abi: sha256} for every engine an AAR packages. The reference for identity."""
    return {abi: aar_engine_digest(aar_path, abi) for abi in aar_engine_abis(aar_path)}


def describe_zip(path: str, limit: int = 12) -> str:
    """A short, human-readable summary of a zip's entries, for error messages.

    Error text is the whole product of a gate like this: the person reading it at 3am
    needs to know whether the APK is empty, holds another ABI, or is not a zip. So the
    message carries the entries rather than just the path.
    """
    if not os.path.isfile(path):
        return f"{path}: no such file"
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    except zipfile.BadZipFile:
        return f"{path}: not a zip archive"
    # The interesting entries first: anything under lib/ or jni/ is the native payload.
    native = [n for n in names if n.startswith((APK_ROOT + "/", AAR_ROOT + "/"))]
    shown = native[:limit] if native else names[:limit]
    suffix = "" if len(names) <= limit else f", … {len(names)} entries total"
    if not names:
        return f"{path}: empty archive"
    if not native:
        return f"{path}: no {APK_ROOT}/ or {AAR_ROOT}/ entry at all ({len(names)} entries){suffix}"
    return f"{path}: {shown}{suffix}"
