# PlexonHomes 2.0.0 Phase 2 execution specification

## Certified starting boundary

- Repository: `ZpkDxGames/PlexonHomes`
- Rollback release/tag: `v1.0.1`
- Exact rollback SHA: `d20bef0a76cd3006df51d46d67a3e48636650d31`
- Phase 2 branch: `phase2/2.0.0-premium-homes`
- Candidate: `2.0.0-rc.1`
- Runtime: Paper 26.2 / Java 25 / PlexonCore 2.0.4
- Build: Maven

## SemVer decision

2.0.0 is required because Phase 1 uses the normalized mutable home name as the database primary identity and exposes that value as `HomeView.id()`. Phase 2 introduces an immutable home UUID, separates the mutable name key, migrates the SQLite schema, preserves the UUID through rename/location updates, and defines revision-bound mutation/event semantics. That is an intentional persistence/API semantic break even though commands remain compatible.

## Phase 1 findings driving implementation

1. Rename deletes the old name-keyed row and inserts a new key, changing logical identity.
2. Cache mutations happen before asynchronous persistence completes; failures can leave runtime and SQLite divergent.
3. Player load completion has no reconnect generation guard and can repopulate stale state after logout.
4. Teleport ownership ends before `teleportAsync` reaches terminal state, allowing duplicate in-flight requests, duplicate charging/refund races and stale callbacks.
5. Reload mutates Bukkit config before candidate validation and numeric policy is silently clamped instead of rejected.
6. SQLite has no authoritative schema version/future-version fail-closed gate.
7. Delete GUI confirmation is name-only and not revision-bound, expiring or one-shot.
8. PlaceholderAPI is declared but no expansion exists.
9. Destination validation does not reject non-finite yaw/pitch.
10. Build/release workflows do not meet Phase 2 provenance, class-major, dependency-isolation or immutable prerelease gates.

## Product boundary

PlexonHomes remains the focused Plexon replacement for the Essentials home subset: set, browse, inspect, teleport, manage, rename/update and remove homes. It will not absorb spawn, hub, back, warps, heal, feed or unrelated utility commands.

## Authoritative model

Each home is `(owner UUID, home UUID)` authoritative identity plus a unique per-owner normalized `nameKey`. Display name, destination and revision are mutable. Rename changes `nameKey` and display name only. Every mutation increments revision. Compatibility `id()` accessors remain as aliases for the mutable name key and are documented as non-authoritative.

## Persistence and migration

Schema v2 stores `home_uuid` and `name_key`, uses `(owner_uuid,home_uuid)` as primary key and `(owner_uuid,name_key)` as unique lookup. A v1 database is backed up before migration. Legacy UUIDs are deterministic from owner UUID + legacy name key so migration is idempotent. Unknown/future schema markers or malformed records fail closed. SQLite work remains on bounded executors; movement/damage/PlaceholderAPI paths never query storage.

## Mutation ownership

Player mutations are serialized per owner. The candidate is validated on the main thread, persisted asynchronously, then committed to cache and post-commit events on the main thread. Persistence failure does not publish speculative cache state. Reconnect/load generation rejects stale asynchronous results.

## Teleport ownership

One attempt token owns resolve -> warmup -> final revalidation -> charge -> `teleportAsync` -> terminal completion. Duplicate requests while any phase is active are rejected. Shared PlexonCore player-watch handles movement/damage/quit/death/world-change cancellation. One shared warmup coordinator replaces per-attempt delayed Bukkit tasks. Cooldown is committed only after successful teleport. Refund is attempted at most once and Vault response success is checked.

## Configuration

`config-version: 2` is mandatory. Candidate parsing validates full numeric ranges, world policy modes, permission settings, fee and migration mode without clamping. Reload parses a separate YAML candidate and atomically swaps the immutable runtime snapshot only on success. A failed reload retains the last known-good snapshot.

## GUI and destructive actions

Rendered cards bind slots to stable home UUID + revision rather than current list index. Management and delete confirmation use stable identity. Delete tickets are actor/owner/home/revision-bound, expiring and one-shot. Stale sessions fail closed. Inventory clicks and drags are cancelled for owned menus.

## PlaceholderAPI and API

The optional `%plexonhomes_*%` expansion is cache-only. Supported concepts include count, limit, remaining, default-home availability and `has_<name>`. Offline/unloaded behavior is deterministic. `HomeView` exposes stable `homeId()` and mutable `nameKey()`; `id()` remains a legacy alias.

## Release closure

Exact-head CI must compile/test, assert Java class major 69, dependency isolation, required resources/classes, exact JAR, checksum, test summary, provenance and whitespace. A single OPEN/DRAFT/UNMERGED PR must point at the exact candidate. `v2.0.0-rc.1` must tag that SHA directly and publish as prerelease with JAR, `SHA256SUMS.txt`, `TEST_SUMMARY.txt`, and `PROVENANCE.txt`. Stable `v2.0.0` is forbidden before actual PlexonCraft migration/behavior/economy/restart/performance/soak certification.
