# PlexonHomes

PlexonHomes is the focused PlexonCraft home module: `/sethome`, `/home`, `/homes`, `/delhome`, `/renamehome` and home administration. It intentionally does **not** replace spawn, hub, back, warps, heal, feed or unrelated Essentials features.

## 2.0.0 release-candidate boundary

`2.0.0-rc.1` introduces stable home UUID identity, SQLite schema v2, persistence-first mutations, stale-load rejection, authoritative async teleport attempts, transactional config reload, revision-bound GUI deletion, cache-only PlaceholderAPI and stricter release provenance. Stable 2.0.0 remains blocked until PlexonCraft runtime certification.

## Runtime

- Paper 26.2
- Java 25
- PlexonCore 2.0.4 / Core API 2.x
- SQLite JDBC bundled in the plugin JAR
- Vault optional; TheosisEconomy remains authoritative through its Vault provider
- PlaceholderAPI optional and never shaded

## Player commands

- `/sethome [name]` — creates a home, or explicitly overwrites the same named home while preserving its stable UUID.
- `/home [name]` — enters the single authoritative safe-teleport pipeline.
- `/homes` — opens the paginated browser. Left click teleports; right click opens management.
- `/delhome <name>` — removes a named home.
- `/renamehome <old> <new>` — changes the lookup/display name without changing the home UUID.

Home names accept letters, digits, `_` and `-`, are normalized case-insensitively, and obey `homes.max-name-length`.

## Limits

The default limit is configured in `limits.default`. Permissions use `limits.permission-prefix` (default `plexonhomes.limit.`); all effective numeric permissions are inspected and the highest valid value wins. `limits.unlimited-permission` bypasses the cap. Reducing a player's limit never deletes existing homes.

## Teleport behavior

A request owns one attempt from destination resolution through warmup and the terminal `teleportAsync` callback. A second request while an attempt is resolving, warming up or in flight is rejected. Movement/damage/quit/death/world-change cancellation uses PlexonCore's shared player-watch runtime. Warmups use one shared coordinator rather than one Bukkit task per teleport. Destination state is revalidated after warmup. Cooldown starts only after successful teleport completion.

`safe-teleport` rejects missing worlds, non-finite coordinates/yaw/pitch, out-of-height/world-border locations and configured hazards. Optional nearby search is bounded by the configured horizontal/vertical radii; malformed saved data fails before fallback search.

## Economy

`teleport.fee` is charged once through Vault. With TheosisEconomy installed, its Vault provider remains authoritative. A failed terminal teleport performs at most one compensating deposit and checks Vault's transaction result. `plexonhomes.teleport.fee.bypass` bypasses the fee.

## Configuration and reload

Phase 2 uses `config-version: 2`. The published v1.0.1 config is upgraded once after candidate validation and backed up to `backups/config-v1-before-phase2.yml`. Malformed numeric values, policy modes, permission configuration and fees are rejected instead of clamped. `/homesadmin reload` parses a separate candidate and atomically swaps the immutable runtime snapshot only on success; the prior runtime remains active after a failed reload.

World policies support `BLACKLIST` and `WHITELIST` under `worlds.set` and `worlds.teleport`. A whitelist must contain at least one world.

## Persistence and v1.0.1 migration

Schema v2 makes `(owner UUID, home UUID)` authoritative and keeps `name_key` unique per owner. Existing v1.0.1 rows are backed up before migration and receive deterministic stable UUIDs derived from owner UUID + legacy name key. Rename no longer deletes/recreates logical homes. Future/unknown schemas and malformed persisted homes fail closed. SQLite reads/writes stay on bounded executors.

Essentials migration remains read-only toward Essentials userdata and preserves unresolved/malformed entries by reporting them instead of deleting source data.

## GUI safety

GUI slots bind to immutable home UUID + revision snapshots. A stale card or management view refreshes instead of acting on a different home. Delete confirmations are actor-, owner-, home- and revision-bound, expire, and are one-shot. Owned menus cancel click and drag inventory mutation paths.

## PlaceholderAPI

The optional identifier is `plexonhomes`. Available cache-only placeholders include `%plexonhomes_count%`, `%plexonhomes_limit%`, `%plexonhomes_remaining%`, `%plexonhomes_default%`, `%plexonhomes_default_available%` and `%plexonhomes_has_<name>%`. No placeholder performs a SQLite query. Unloaded/offline results are deterministic.

## Public API/events

The Bukkit service `PlexonHomesAPI` exposes immutable `HomeView` records. `homeId()` is the stable identity; `nameKey()` is mutable. `id()` remains a 1.x source-compatibility alias for the name key and must not be persisted as authoritative identity by integrations. Mutation events are emitted only after the authoritative SQLite mutation succeeds and the cache commits.

## Administration

`/homesadmin diagnostics` reports Core, schema, cache, mutation, teleport, player-watch, SQLite, PAPI and migration state. `/homesadmin backup` creates a WAL-checkpointed database copy. `/homesadmin inspect <uuid>` performs bounded asynchronous persisted-home inspection without requiring the owner online. Essentials import remains under `/homesadmin migrate scan|plan|execute|status`.

## Backup and rollback

Before deploying the release candidate, back up `plugins/PlexonHomes/`. Schema migration creates its own pre-migration database backup. The published rollback is `v1.0.1` at `d20bef0a76cd3006df51d46d67a3e48636650d31`. A v2 database must not be opened by 1.0.1; rollback requires restoring the matching pre-v2 data backup.

## Performance contract

No storage lookup occurs from movement/damage listeners or PlaceholderAPI. Home profiles are per-player immutable map snapshots, offline profiles are unloaded, stale async loads are rejected by generation, persistence executors are bounded, teleport lookup is O(1) by player, and config parsing is not repeated per teleport.

## Runtime certification

Stable promotion requires representative v1.0.1 migration, command/GUI/limits/rename/delete behavior, missing/unsafe destination cases, warmup/cooldown/cancellation, duplicate and stale async attempts, Vault fee/refund tests when enabled, restart/reconnect/reload rollback, PAPI/API/Core/protection interoperability, Spark/MSPT comparison, at least 30 minutes soak and zero HIGH/CRITICAL defects. See `docs/RUNTIME_CERTIFICATION.md`.
