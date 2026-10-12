# Lifecycle and platform facts — moved

This file was split in the Stage-2 knowledge refresh because it covered two subsystems
that change for different reasons and have different owners.

| Read this | For |
|---|---|
| `modules/platform-facts.md` | `PlatformFacts`, `ScreenFactState`: raw Android facts, level-vs-edge, the observation epoch, the lock boundary, listener ordering and reentrancy, session attach/detach and the bounded drain |
| `modules/lifecycle-services.md` | `BoxService`, `VPNService`, `ProxyService`, `CoreLifecycle`: core ownership, the TUN fd, start/stop convergence, the notification's generation gate, and the settings that are read but never written |

Nothing was dropped; both files are denser than this one was. If you arrived here from an
older reference, the corresponding content is:

| Old line | Now |
|---|---|
| "PlatformFacts is canonical collector and serial bridge…" | `modules/platform-facts.md` §"What it is, and what it is not" |
| "Session ownership and bounded drain prevent old facts reaching a new core" | `modules/platform-facts.md` §"Session ownership and bounded drain" |
| "Listeners run outside the fact lock…" | `modules/platform-facts.md` §"Lock boundary and listener ordering" |
| "Initial screen seeding captures epoch before platform read…" | `modules/platform-facts.md` §"The seed: epoch first, platform read second" |
| "JVM tests cover ordering…" | `modules/tests-evidence.md` |
