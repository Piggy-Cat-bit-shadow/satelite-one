# Lifecycle and platform facts

PlatformFacts is canonical collector and serial bridge. Foreground/screen are levels, USER_PRESENT is an edge, trim is original Android integer; network is not a wake substitute.

Session ownership and bounded drain prevent old facts reaching a new core (`51cb7f5`, `1a86247`). Listeners run outside the fact lock because reentrancy was an ordering risk (`36dee73`, `15b5ff1`). Initial screen seeding captures epoch before platform read; any later observation, including the same level, invalidates it (`481e370`, `15c432d`).

JVM tests cover ordering, seed wiring, lock boundary, session handover, trim and start guards. They do not prove OEM scheduling, Doze, physical unlock or actual revoke.
