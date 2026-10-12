# Architecture

```text
Compose UI / ViewModels
  | command client, configuration and DTOs
BoxService --- VPNService (TUN) / ProxyService (headless URL test)
  | SingBoxCore -> libbox CommandServer -> libbox.aar -> pinned sing-box
```

PlatformFacts is the application-owned Android fact bridge. PlatformInterfaceWrapper supplies Android facilities to libbox. DefaultNetworkMonitor forwards physical-network changes; it is not a wake policy. CommandClient does not own the core or infer liveness from page visibility; one UI owner derives its lifetime from canonical foreground/core state (`3c65418`, `3813a23`).
