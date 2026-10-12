# Core integration

## Ownership boundary

The app hosts SingBoxCore/libbox CommandServer, supplies Android interfaces, packages the AAR and presents data. The pinned core owns protocol, TUN/runtime policy, command operations, trafficcontrol and libbox API semantics. Do not recreate core policy in Kotlin.

## Design and evidence

There is one command socket. CommandClient is not a core-liveness oracle. Headless ProxyService can URL-test while disconnected but has no TUN and must not create a connected UI state. Traffic/status is core-sourced; no external controller is configured, and unavailable data is not displayed as zero (`919089d`, `fb5a361`).

Platform facts are facts only. Existing AAR evidence did not expose a distinct resume-edge API; do not repurpose wake as one without an explicit core contract. Wrapper/lifecycle tests prove app policy paths, not all core behaviour: inspect `1965bbf351710b86722838ae61a91b62c97d29de` for core semantics.
