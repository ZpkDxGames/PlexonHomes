# Phase 3 — Homes Consolidation Audit

Status: AUDIT CHECKPOINT — SET HOME DECOMMISSION BLOCKED

Baseline: `phase2/2.0.0-premium-homes` @ `bf296d8ff36c9aef132b60205909e4782582168d`

## Canonical target ownership

PlexonHomes is the intended canonical owner of:

- `/home`
- `/homes`
- `/sethome`
- `/delhome`
- home limits
- safe home teleport behavior

The current Phase 2 candidate also provides `/renamehome`, a homes GUI, admin tooling, safe teleport checks, warmup/cooldown/fee policy, PlaceholderAPI support and persisted homes.

## Current external overlap

SetHome 6.3 remains active on PlexonCraft and overlaps at least `/home`, `/sethome` and `/delhome`. Its current public configuration model also includes home cooldown, cancel-on-move and rank-based home limits.

This makes SetHome a **decommission candidate**, but not yet removable.

### Hard blocker

PlexonHomes currently implements an Essentials userdata importer, but no provider-specific SetHome importer exists in the source tree.

The actual production SetHome data path and serialization format have not been captured in the available audit evidence. Phase 3 must not guess that format.

Therefore SetHome removal is blocked until an operator supplies or audits the live `plugins/SetHome` data/config files and the migration adapter is implemented against that exact format.

## Existing Essentials migration

The current Essentials importer is read-only with respect to source data and supports scan/plan/execute behavior. It normalizes names, resolves worlds and imports through the PlexonHomes service.

Before using it for any historical data, Phase 3 still requires:

- source backup;
- target `homes.db` backup/checkpoint;
- dry-run review;
- duplicate handling review;
- unresolved/invalid world quarantine/reporting;
- repeated-run idempotency test;
- post-import count/name/location comparison;
- restart persistence test.

## Required SetHome migration design

Once the exact live format is known, implement a provider-specific, read-only adapter with the same safety model:

1. `scan`: identify source files/records, players, homes, referenced worlds and invalid records without target writes;
2. `plan`: produce source -> target mapping, normalized names, rank/limit implications and conflicts;
3. `execute`: disabled by default and operator-gated;
4. `status`: report imported/skipped/unresolved/quarantined counts and source fingerprint;
5. source data is never modified or deleted;
6. re-running execute is idempotent;
7. unresolved worlds/invalid locations are reported/quarantined, never silently discarded;
8. rollback retains original SetHome data and a pre-import PlexonHomes DB snapshot.

Do not implement the parser until the real production format is known.

## Permission migration

Only apply mappings for nodes actually present in the live LuckPerms export.

Known SetHome public permission semantics should map as follows after production verification:

| SetHome node | PlexonHomes target |
|---|---|
| `homeplugin.home` | `plexonhomes.use` |
| `homeplugin.sethome` | `plexonhomes.sethome` |
| `homeplugin.delhome` | `plexonhomes.delete` |
| `homeplugin.admindelhome` | `plexonhomes.admin.manage` |
| `homeplugin.adminlisthomes` | `plexonhomes.admin.manage` |
| `homeplugin.limit.<rank>` | no direct textual mapping; resolve each rank to its configured numeric cap and grant `plexonhomes.limit.<N>` |

Rank-limit migration procedure:

1. read live SetHome `max-homes` values;
2. map each rank/group to its numeric maximum;
3. grant the corresponding `plexonhomes.limit.<N>` to the same LuckPerms group;
4. verify effective limit in game;
5. only then remove the old `homeplugin.limit.<rank>` node.

Historical Essentials nodes, when actually present, should be mapped to the equivalent `plexonhomes.use`, `plexonhomes.sethome` and `plexonhomes.delete` permissions only after command ownership is validated.

## Placeholder migration

Current PlexonHomes expansion identifier is `plexonhomes`. Available cache-only placeholders include:

- `%plexonhomes_count%`
- `%plexonhomes_default%`
- `%plexonhomes_default_available%`
- `%plexonhomes_has_<name>%`
- `%plexonhomes_limit%`
- `%plexonhomes_remaining%`

Do not mechanically replace `%essentials_*%` placeholders. First find each live consumer and verify semantic equivalence.

## Feature parity gate before SetHome removal

Required operator/runtime proof:

- same expected homes visible after migration;
- `/home`, `/sethome`, `/delhome`, `/homes` behavior validated;
- rank limits preserved;
- destination world, coordinates, yaw/pitch preserved where valid;
- safe-teleport differences documented as intentional;
- warmup/cooldown/cancel-on-move behavior accepted;
- permissions validated for normal players and staff;
- restart persistence passes;
- source SetHome data backup retained.

## Rollback

If cutover fails:

- disable PlexonHomes standard ownership as required by the operator plan;
- re-enable/restore SetHome command ownership;
- restore old LuckPerms nodes from export;
- restore the pre-import PlexonHomes `homes.db` snapshot if needed;
- leave SetHome source data untouched.

No production plugin is removed by this Phase 3 audit commit.
