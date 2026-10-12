# Build, provenance and release

```text
core pin -> build_libbox writes AAR + provenance -> Gradle BuildConfig
-> binary search gate -> per-ABI APK -> SHA256/build-info/apksigner -> publish
```

Current pin and local producer record: `1965bbf351710b86722838ae61a91b62c97d29de`.
The producer record has both `commit` and `version` set to this full SHA. Shipping
ABIs are `arm64-v8a` and `x86_64`; there is no universal, 32-bit, or legacy target.

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

Without gitignored `signing.properties`, release is unsigned and never falls back to
debug signing. `apksigner` measurement—not a filename—defines state. A dry run never
reads signing secrets or publishes. Only the tag/non-dry-run/signed-by-user path may
release; verified-bundle gates are fail-closed (`a0e2722`, `c7cc03d`, `61b807e`,
`722d22b`). Manual dev CI can select a core for diagnosis but cannot override the
release pin (`58b8309`, `f133dc9`).

## AI cautions

- Do not replace a pin check with `CORE_COMMIT`/checkout metadata: those describe
  a build environment, not necessarily the AAR that was linked.
- Do not add an ABI without changing the Gradle split list, AAR gate defaults,
  final-APK guard, and release evidence together.
- A local absent AAR is supported by Gradle for development; it is never evidence
  that a release artifact is valid.
