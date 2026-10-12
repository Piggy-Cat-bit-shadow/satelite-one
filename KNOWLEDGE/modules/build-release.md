# Build, provenance and release

```text
core pin -> build_libbox writes AAR + provenance -> Gradle BuildConfig
-> binary search gate -> per-ABI APK -> SHA256/build-info/apksigner -> publish
```

Current pin and local producer record: `1965bbf351710b86722838ae61a91b62c97d29de`. Shipping ABIs: arm64-v8a and x86_64; no universal/32-bit/legacy target.

Gradle AAR gate is self-consistency against producer provenance; it does not prove development CI used the release pin. Release CI separately resolves/fetches coreCommit, and ABI guard rejects a split without its own engine (`25ac148`).

Without gitignored signing.properties, release is unsigned and never falls back to debug signing. apksigner measurement—not filename—defines state. Dry run never reads signing secrets/publishes. Only tag/non-dry-run/signed-by-user path may release; verified-bundle gates are fail-closed (`a0e2722`, `c7cc03d`, `61b807e`, `722d22b`). Manual dev CI can pin a core but cannot override release pin.
