# Build, provenance and release

```text
core pin -> build_libbox writes AAR + provenance -> Gradle BuildConfig
-> binary search gate -> per-ABI APK -> SHA256/build-info/apksigner -> publish
```

Current pin and local producer record: `1965bbf351710b86722838ae61a91b62c97d29de`.
The producer record has both `commit` and `version` set to this full SHA. Shipping
ABIs are `arm64-v8a` and `x86_64`; there is no universal, 32-bit, or legacy target.

## Name the two files

Most confusion in this subsystem comes from not naming them:

| File | Role | Tracked? |
|---|---|---|
| `version.properties` → `coreCommit` | the **release pin**, a full 40-char lowercase SHA. Release CI resolves this and proves it fetchable | **yes** |
| `app/libs/libbox.provenance` | the **AAR's own producer record**, written next to the AAR by `build_libbox`. `commit` and `version` must be comparable to each other | **no — gitignored** |
| `app/libs/libbox.aar` | the engine actually linked. Four `jni/<abi>/libbox.so` entries | **no — gitignored** |

`release-apk.yml` requires `^[0-9a-f]{40}$` for the pin. `f133dc9` records a build that
failed the gate for exactly this reason — a short SHA.

The producer record is the **only** copy: it is not in git and there is no second one.
Deleting it destroys the ability to make any provenance claim about the local AAR.

**`README.md`'s build recipe predates `89951ae`.** It shows `cp libbox.aar …` and never
copies `libbox.provenance`, nor mentions `coreCommit`. Following it verbatim leaves the
provenance unknown, and `:app:verifyCoreProvenance` then either throws or advertises the
checkout's HEAD — the exact mislabelling `89951ae` exists to eliminate. The correct recipe
is in the workflows, which copy **both** files.

## Separate checks, separate claims

| Check | It proves | It does not prove |
|---|---|---|
| `check_core_provenance.py` in release CI | the requested `version.properties` SHA equals both producer fields; every shipping ABI has `jni/<abi>/libbox.so`; a packaged `libbox.so` contains that SHA | that an APK copied from a different job is the verified output |
| `:app:verifyCoreProvenance` | an AAR used by Gradle is self-consistent with its own producer record and its binary carries the advertised comparable identity | that development CI followed the release pin; development CI may intentionally use `testing` or an explicit dispatch SHA |
| release ABI/engine guard | each final split contains the engine digest for *its own* ABI, not merely an engine for another ABI | signing or GitHub publication state |
| SHA256/build-info and verified-bundle checks | the named, final files are the files checksummed, described, uploaded, and eligible for publish | that a signer is trusted by a target device |
| `apksigner verify --print-certs` | observed signature state/certificate evidence of the produced APK | any claim based only on a filename or Gradle variant |

This separation is deliberate: revision identity, AAR self-consistency, per-ABI
engine presence, artifact identity, and signing are different failure modes. The
single-ABI-AAR failure mode is retained as a negative test by `25ac148`.

Without gitignored `signing.properties`, the release variant is built **unsigned** and
never falls back to debug signing. `apksigner` measurement — not a filename — defines
state. A dry run never reads signing secrets and never publishes. Only the
tag / non-dry-run / signed-by-user path may release; the verified-bundle gates are
fail-closed (`a0e2722`, `c7cc03d`, `61b807e`, `722d22b`). Manual dev CI can select a core
for diagnosis but cannot override the release pin (`58b8309`; the isolation is
`89951ae` plus the fetch validation — `f133dc9` only moved the pin's value).

## Not yet executed

The gate chain above is read from the workflow and its scripts; **it has never run in its
current form.** `25ac148` added the per-ABI engine guard and `_zipcontent.py` **after** the
newest release-workflow run, and no CI run exists on any commit newer than `ae89388`.
Treat this section as the intended design, not as a demonstrated pipeline.

Two known weaknesses in it, both proved by fault injection:

- On the publish-preflight path (no `--aar`) the verifier compares each APK's engine
  against `core_engine_sha256` **from `build-info.json`** — i.e. against itself. A bundle
  whose APKs carry a tampered engine and whose metadata records those same digests
  **passes**. Adding the real AAR flips it to a failure, which is why `--require-aar`
  matters — and why it is worth knowing that **nothing asserts that flag is present**.
- `test_release_abi_guard.py` cases N5 and N5b build byte-identical fixtures, so N5b is a
  duplicate rather than the "forged metadata" case its comment claims.

## AI cautions

- Do not replace a pin check with `CORE_COMMIT`/checkout metadata: those describe
  a build environment, not necessarily the AAR that was linked.
- **A green `:app:verifyCoreProvenance` means "the packaged binary matches its own
  provenance record", never "this is the pinned core".** Its expectation comes from
  `libbox.provenance` itself, so a relabelled AAR is invisible to it — `6442c95` is a real
  tree where that gate was green while the Python gate correctly failed. Never delete
  `check_core_provenance.py` as a duplicate.
- **One ABI correct is not enough.** `25ac148` exists because an x86_64-only AAR passed
  every revision check while the `arm64-v8a` split shipped with no engine. Any new gate
  must open the APK.
- The AAR legitimately carries **four** engines (`armeabi-v7a`, `arm64-v8a`, `x86`,
  `x86_64`) while only two are split into APKs. The two extra are expected and are not a
  reason to add splits. `check_core_provenance.py`'s `DEFAULT_ABIS` must stay equal to the
  Gradle `splits.abi.include` list.
- Do not add an ABI without changing the Gradle split list, AAR gate defaults,
  final-APK guard, and release evidence together.
- A local absent AAR is supported by Gradle for development; it is never evidence
  that a release artifact is valid.
- Do not treat a tag as proof of a release. There are 15 tags and **zero releases**, and
  the newest release-gate code has never executed.
