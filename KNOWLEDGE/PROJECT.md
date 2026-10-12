# Project

Jiejiebox is an Android 7.0+ Kotlin/Compose client for exactly one in-process custom sing-box `libbox.aar`. The app owns Android integration, import/configuration and presentation. The core owns protocol semantics, runtime/TUN policy, command server, traffic/status and libbox API behaviour.

The old multi-core/sidecar design was removed by `d2a2e98` and `d628ef0`. Do not recreate selectors or app-owned routing/DNS/power policy from old history. Raw sing-box configuration passes through without hidden policy injection (`aec3893`, `ebd2f79`).
