# Commit index

| Commit(s) | Area | Classification | Lasting point |
|---|---|---|---|
| `d2a2e98`, `d628ef0`, `ebd2f79` | product boundary | REFACTOR, OWN | single core; no app route policy |
| `a3d2321`, `51cb7f5`, `1a86247`, `a5a6783` | platform facts | CORE-INTEGRATION,HARDENING | raw facts and bounded delivery |
| `7275c0f`, `481e370`, `15c432d` | screen seed | FIX,TEST | observation outranks snapshot |
| `36dee73`, `56eb255`, `15b5ff1` | foreground | FIX,HARDENING | ordering and reentrancy safety |
| `3c65418`, `3813a23`, `d10c9a5`, `2cb6eb9` | UI client | FEATURE,FIX,TEST | lifetime verdict controls client/establish |
| `74ff3ce`, `bdb4ced`, `7d4218a`, `5d9f4f2` | service | FIX,HARDENING | stale lifecycle cannot win |
| `8432caf`, `ed014c6` | network | FIX,COMPAT | epoch and owner-scoped work |
| `89951ae`, `f133dc9`, `25ac148` | provenance | RELEASE,HARDENING | producer record and split ABI gate |
| `a0e2722`, `c7cc03d`, `61b807e`, `722d22b` | release CI | CI,RELEASE | measured, verified, fail-closed publish |
| `58b8309`, `f2cde01` | dev CI | CI | manual pin isolates upstream failure |

Older multi-core history is **SUPERSEDED** by `d2a2e98`; inspect only for migration archaeology.
