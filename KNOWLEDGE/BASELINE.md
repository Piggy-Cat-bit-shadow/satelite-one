# Baseline

| Field | Value |
|---|---|
| Generated | 2026-10-12 (Asia/Shanghai) |
| Repository / branch | `Piggy-Cat-bit-shadow/satelite-one` / `main` |
| Production implementation baseline | `2cb6eb9b8e3721036534ef84b1e4352369ac1484` |
| Documentation-only knowledge tip at capture | `6864a1ef9295d865ef09b10614ef657efb3a14f4` |
| Latest tag baseline | `v0.5.10` / `6f4124c` |
| Core repository/ref | `Piggy-Cat-bit-shadow/sing-box` / `testing` |
| Release pin and producer provenance | `1965bbf351710b86722838ae61a91b62c97d29de` |
| Shipping ABIs | `arm64-v8a`, `x86_64` |
| Working tree at capture | clean |

`2cb6eb9` is the last production-code commit deliberately covered by this Stage-1
baseline. `2cb6eb9..6864a1e` contains only evidence/knowledge documentation; it
does not change Android runtime or release behavior. The tag is not proof of a
built/published release. The local producer record has the same SHA for commit
and version.

For normal implementation work, use the **production implementation baseline**
as the lower bound for code archaeology. First inspect its delta to the checked-out
HEAD, then classify any documentation-only commits separately rather than treating
them as runtime changes. Source and Git remain authoritative if this file and HEAD
ever disagree.
