# Hard invariants

- **One core:** ship only the recorded sing-box libbox; no engine selector.
- **Raw facts, one policy owner:** Android forwards verbatim trim, foreground, screen and user-present; it does not map levels, pause/wake/reconnect or duplicate core policy (`a3d2321`, `a5a6783`).
- **Canonical lifetime gates establishment:** stale generation/stop or absent UI-client verdict cannot revive core or notification (`74ff3ce`, `d10c9a5`, `2cb6eb9`).
- **Order and lock boundary:** record facts before effects; listeners run outside the fact lock. Newer screen observations, including same-value ones, invalidate initial snapshots (`36dee73`, `15b5ff1`, `481e370`).
- **Session ownership:** delivery, notifications and network listeners belong to their registering session/generation (`1a86247`, `24c8372`, `bdb4ced`).
- **TUN ownership:** VPNService establishes/passes the fd. ProxyService is no-TUN/headless.
- **Binary provenance:** version text is not engine proof; AAR record, binary inspection, ABI split, checksum and measured signature cover separate links.

Compose, per-ABI splits and testing as default development CI ref are conventions, not eternal invariants.
