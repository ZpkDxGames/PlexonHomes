# SetHome -> PlexonHomes Phase 3 Migration

Status: IMPLEMENTED FOR REVIEW — DO NOT EXECUTE ON PRODUCTION WITHOUT OPERATOR APPROVAL

## Proven live SetHome source

PlexonCraft SetHome 6.3 stores homes in `plugins/SetHome/homes.yml` using this shape:

```yaml
<PLAYER_UUID>:
  <HOME_NAME>:
    world: <scalar>
    x: <scalar>
    y: <scalar>
    z: <scalar>
    yaw: <scalar>
    pitch: <scalar>
```

Live configuration relevant to cutover is:

```yaml
cooldown: 0
max-homes:
  default: 15
cancel-on-move: false
play-sound: true
```

Commented rank examples are not production configuration and are not migration inputs.

## Dedicated provider

SetHome is parsed by `SetHomeMigrationService`. The existing Essentials migration parser is not reused or changed to imitate this schema.

Operator surface:

- `/homesadmin migrate sethome scan`
- `/homesadmin migrate sethome plan`
- `/homesadmin migrate sethome execute`
- `/homesadmin migrate sethome verify`
- `/homesadmin migrate sethome status`

The Phase 2 shorthand `/homesadmin migrate scan|plan|execute|status` remains an Essentials migration compatibility path.

## Safety contract

`scan`, `plan`, `verify`, and `status` do not write to `homes.yml`. The source file is never deleted or rewritten.

Planning classifies every source record as one of:

- `IMPORT`
- `SKIP_ALREADY_IMPORTED`
- `SKIP_CONFLICT`
- `QUARANTINE_INVALID_WORLD`
- `QUARANTINE_INVALID_COORDINATE`
- `QUARANTINE_INVALID_RECORD`

Existing PlexonHomes entries are never silently overwritten. Name conflicts are skipped and reported.

A SetHome record receives a deterministic migration UUID from its provider name, player UUID, and exact SetHome home name. A second migration therefore detects the existing stable identity as `SKIP_ALREADY_IMPORTED` even if that imported home was later renamed in PlexonHomes.

`execute` requires a preceding successful `plan`. It re-reads the source and destination before mutation and refuses to proceed when the source fingerprint changed after planning.

## Validation

The provider requires:

- a valid top-level player UUID;
- a SetHome home mapping;
- a home name accepted by PlexonHomes (`[A-Za-z0-9_-]+`, maximum configured length);
- an exact, currently loaded and PlexonHomes-allowed world name;
- finite numeric X/Y/Z;
- finite yaw/pitch representable by PlexonHomes.

It never creates or loads a missing world, rewrites a world name, falls back to another world, rounds X/Y/Z, or silently renames a home.

PlexonHomes stores X/Y/Z as doubles and yaw/pitch as floats. The importer preserves the full precision supported by those fields.

## Scan/report contract

Reports contain operational metadata only:

- source found/missing;
- SHA-256 source fingerprint;
- player count;
- total home count;
- valid/invalid count;
- invalid-coordinate and malformed-record count;
- unavailable-world count;
- conflict count;
- already-imported count;
- planned-import count;
- imported/rejected execution count;
- quarantined count;
- lifecycle status.

Normal reports do not print player UUIDs, home names, or coordinates.

## Home-limit preservation

The only active SetHome production limit is `max-homes.default: 15`. PlexonHomes already has an authoritative numeric default at `limits.default`, so the Phase 3 bundled default is `15`.

No rank-specific SetHome permissions are inferred from commented `vip`, `premium`, or `admin` examples. No rank-specific LuckPerms conversion is required from the supplied live configuration.

An existing production `plugins/PlexonHomes/config.yml` is not silently rewritten merely because the bundled default changed. Before cutover, the operator must explicitly set:

```yaml
limits:
  default: 15
```

and verify any existing `plexonhomes.limit.<N>` / `plexonhomes.limit.unlimited` grants separately.

## Teleport parity

SetHome currently has `cooldown: 0` and `cancel-on-move: false`. PlexonHomes currently defaults to a 3-second warmup, 10-second cooldown and movement cancellation.

For closest player-visible parity during the SetHome cutover, the deployment configuration should explicitly use:

```yaml
teleport:
  warmup-seconds: 0
  cooldown-seconds: 0
  cancel-on-movement: false
```

This is an operator configuration recommendation, not an automatic migration mutation.

PlexonHomes safe-destination/world policy remains an intentional Phase 3 safety behavior. PlexonHomes does not currently expose a SetHome-equivalent `play-sound` option; no sound subsystem is added solely for legacy presentation parity.

## Production procedure

Before execution:

1. Back up the complete `plugins/SetHome/` directory, including `homes.yml` and `config.yml`.
2. Back up `plugins/PlexonHomes/homes.db` using `/homesadmin backup` and retain an external copy.
3. Export relevant LuckPerms state.
4. Record the current PlexonHomes configuration.
5. Prevent SetHome home writes during the migration/cutover window using an operator-approved maintenance or command-ownership procedure; do not delete its files.
6. Set and reload the approved PlexonHomes cutover configuration, including `limits.default: 15`.
7. Run `/homesadmin migrate sethome scan` and capture the fingerprint/counts.
8. Run `/homesadmin migrate sethome plan` and review all conflict/quarantine/already-imported counts.
9. Only after approval, run `/homesadmin migrate sethome execute`.
10. Run `/homesadmin migrate sethome verify`; expected successful imports should now be reported as already imported.
11. Validate `/home`, `/homes`, `/sethome`, `/delhome`, limits, worlds, precision, restart persistence and player-visible teleport behavior.
12. Keep SetHome disabled-but-available through the rollback window. Do not remove it until a later decommission approval.

## Rollback

If cutover validation fails:

1. stop new PlexonHomes mutations;
2. restore the pre-import PlexonHomes `homes.db` backup and configuration;
3. restore the relevant LuckPerms export if permissions changed;
4. restore SetHome command authority / re-enable SetHome;
5. retain the original SetHome directory and `homes.yml` unchanged for investigation.

The importer itself never deletes SetHome data and never bulk-rewrites the PlexonHomes database.
