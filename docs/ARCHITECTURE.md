# Architecture

PlexonHomes is deliberately limited to home ownership and home teleportation. Player activity monitoring is delegated to the shared `PlayerWatchService` introduced in PlexonCore 2.0.3. Homes owns policy: limits, safe destinations, warmup, cooldown, fee, persistence and events.

Profiles are loaded asynchronously from SQLite and cached as immutable copy-on-write maps. Mutations update authoritative memory first and queue durable writes. Teleport destination block checks remain on the Paper primary thread; unloaded destination chunks use Paper async loading before final validation.
