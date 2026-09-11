# PlexonHomes

**PlexonHomes 2.0.0** is the stable focused PlexonCraft home module for `/sethome`, `/home`, `/homes`, `/delhome`, `/renamehome`, home administration, safe teleport and migration tooling. It intentionally does **not** replace spawn, hub, back, warps, heal, feed or unrelated Essentials features.

Repository/source/release stability is separate from live SetHome cutover. Stable publication does not execute migration and does not authorize SetHome removal; both remain operator-gated deployment steps and may remain `NOT_EXECUTED` in release provenance.

## Runtime

- Paper `26.2.build.121-stable`
- Java 25 / class major 69
- PlexonCore 2.0.4 / Core API 2.x
- SQLite JDBC bundled in the plugin JAR
- Vault optional; TheosisEconomy remains authoritative through its Vault provider
- PlaceholderAPI optional and never shaded

## Stable 2.0.0 reliability boundary

The accepted Phase 2 / Phase 3 runtime line is preserved:

- stable home UUID identity with mutable display/name key;
- SQLite schema v2 and persistence-first mutations;
- generation-bound stale async load rejection;
- one authoritative teleport attempt from destination resolution through warmup and terminal `teleportAsync` completion;
- duplicate travel attempts rejected while an attempt is resolving/warming/in-flight;
- final destination revalidation before teleport and cooldown only after successful completion;
- at-most-once compensating Vault refund after a charged failed terminal teleport;
- transactional candidate-first config reload retaining the previous runtime on failure;
- actor/owner/home/revision-bound one-shot destructive confirmation and stale-GUI rejection;
- cache-only PlaceholderAPI and immutable public API views;
- bounded executors and no movement/damage/PAPI storage lookups.

## SetHome migration

Stable 2.0.0 includes the dedicated Phase 3 `SetHomeMigrationService` for the proven SetHome 6.3 `plugins/SetHome/homes.yml` schema.

Operator workflow:

```text
/homesadmin migrate sethome scan
/homesadmin migrate sethome plan
/homesadmin migrate sethome execute
/homesadmin migrate sethome verify
/homesadmin migrate sethome status
```

The source file is read-only. Planning creates no target writes. Execute requires a fresh successful plan and matching SHA-256 source fingerprint. Existing PlexonHomes records are never silently overwritten. Invalid worlds/coordinates/records are quarantined/reported. Deterministic provider identities make successful imports idempotent even after later home renames.

The verified SetHome production default limit is reflected by bundled `limits.default: 15`. Existing production configuration is not silently rewritten, so operators must explicitly validate the live limit and permissions during cutover.

**Do not remove SetHome solely because 2.0.0 is stable.** Back up both products, review scan/plan output, execute only with operator approval, verify counts/locations/limits/restart behavior, and retain SetHome through the rollback window. See `docs/MIGRATION_SETHOME.md`.

## Player commands

- `/sethome [name]` — create a home or explicitly overwrite the same named home while preserving stable UUID identity.
- `/home [name]` — enter the authoritative safe-teleport pipeline.
- `/homes` — open the paginated browser; left click teleports and right click manages.
- `/delhome <name>` — remove a named home.
- `/renamehome <old> <new>` — rename without changing the home UUID.

Home names accept letters, digits, `_` and `-`, are case-insensitively normalized, and obey `homes.max-name-length`.

## Limits and safe teleport

The default limit is `limits.default`. Numeric `plexonhomes.limit.<N>` permissions resolve to the highest effective value; `plexonhomes.limit.unlimited` bypasses the cap. Reducing a limit never deletes existing homes.

`safe-teleport` rejects missing worlds, non-finite coordinates/yaw/pitch, out-of-height/world-border locations and configured hazards. Nearby search is bounded by configured horizontal/vertical radii, and malformed persisted data fails before fallback search.

## Persistence and upgrades

Schema v2 makes `(owner UUID, home UUID)` authoritative while keeping `name_key` unique per owner. Existing v1.0.1 rows receive deterministic stable UUIDs after a pre-migration backup. Future/unknown schemas and malformed persisted homes fail closed. SQLite reads/writes stay on bounded executors.

Rollback baseline: `v1.0.1` / `d20bef0a76cd3006df51d46d67a3e48636650d31`. A v2 database must not be opened by v1.0.1; rollback requires restoring the matching pre-v2 data backup.

## Build and stable publication

CI provisions the exact released PlexonCore 2.0.4 JAR and pinned SHA-256, runs the complete Maven suite, requires a non-empty all-green test result, verifies Java 25/class-major 69, required resources/API/migration/SQLite entries, dependency isolation, checksum and provenance.

Stable output:

```text
target/PlexonHomes-2.0.0.jar
```

The stable publisher accepts only `release/stable` pointing at exact current `main`, proves accepted Phase 3 ancestry, rebuilds that source, publishes JAR + `SHA256SUMS.txt` + `TEST_SUMMARY.txt` + `PROVENANCE.txt`, downloads those release assets again and verifies checksum/exact-source provenance before finishing green.

Live PlexonCraft migration/cutover, soak, Spark/MSPT and SetHome decommission validation remain operational follow-up evidence rather than GitHub stable-release prerequisites.
