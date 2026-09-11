# Phase 3 — Homes Consolidation Audit

Status: **SOURCE CONSOLIDATION COMPLETE — PRODUCTION CUTOVER OPERATOR-GATED**

Accepted Phase 3 implementation head: `61aa0724848e84c47c674432333b180c20b41a6e`.

## Canonical target ownership

PlexonHomes is the intended canonical owner of `/home`, `/homes`, `/sethome`, `/delhome`, home limits and safe home teleport behavior. It also provides `/renamehome`, the homes GUI, administration, PlaceholderAPI, public API/events and persisted home state.

## SetHome overlap and migration

SetHome 6.3 remains a production decommission candidate, but stable PlexonHomes publication does not itself remove or disable it.

The earlier Phase 3 blocker—unknown SetHome production serialization—was resolved with verified live evidence. PlexonCraft SetHome stores homes in `plugins/SetHome/homes.yml` under player UUID → home name → world/x/y/z/yaw/pitch mappings. The accepted Phase 3 source implements a dedicated `SetHomeMigrationService` against that exact model.

The provider supports:

- read-only `scan`;
- read-only `plan` with source SHA-256 fingerprint;
- explicitly invoked `execute` requiring the approved unchanged fingerprint;
- read-only `verify` and `status`;
- deterministic provider identities for idempotency;
- conflict skip instead of overwrite;
- quarantine/reporting for invalid worlds, coordinates and malformed records;
- no source-file mutation or deletion.

See `docs/MIGRATION_SETHOME.md` for the complete operator procedure.

## Verified limit policy

The active SetHome production default is 15 homes. The bundled PlexonHomes default is therefore `limits.default: 15`. Existing installed PlexonHomes configuration is not automatically rewritten; the operator must verify the production value and any `plexonhomes.limit.<N>` / unlimited grants before cutover.

No rank-specific mappings are inferred from commented SetHome examples.

## Permission and placeholder migration

Only migrate permission nodes actually observed in live LuckPerms state. Do not infer group mappings from documentation/examples.

PlexonHomes cache-only placeholders use the `plexonhomes` identifier, including count/default/availability/limit/remaining/home-presence state. Do not mechanically replace unrelated Essentials/SetHome placeholders without verifying each live consumer and semantic equivalence.

## Production cutover gate

Before SetHome removal:

1. back up the complete SetHome directory and PlexonHomes database/config;
2. export relevant LuckPerms state;
3. stop conflicting SetHome writes during the approved cutover window without deleting source data;
4. configure and verify the intended PlexonHomes limit/warmup/cooldown/movement policy;
5. run SetHome scan and plan and review all counts/conflicts/quarantine entries;
6. execute only after operator approval;
7. verify expected records become already-imported and validate commands/GUI/limits/worlds/precision;
8. restart and verify persistence;
9. retain SetHome disabled-but-available through the rollback window;
10. remove SetHome only under a later explicit decommission approval.

These live operations are deployment evidence. They are intentionally **non-blocking for verified GitHub stable source/release closure**.

## Rollback

If cutover validation fails, stop new target mutations, restore the pre-import PlexonHomes DB/config, restore relevant permission state, restore SetHome command authority and retain the original SetHome files unchanged for investigation.
