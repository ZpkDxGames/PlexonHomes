# Migrating PlexonHomes v1.0.1 to 2.0.0

1. Stop the server and back up `plugins/PlexonHomes/`.
2. Install the 2.0.0 release candidate with PlexonCore 2.0.4.
3. On first start, the legacy config is validated, backed up, then written as config v2 with the delete-confirmation setting.
4. SQLite schema v1 is detected by shape/marker. A database backup is created before v2 migration.
5. Each legacy `(owner_uuid, home_id)` row receives a deterministic `home_uuid`; the old home ID becomes `name_key`.
6. Row counts and record validity are checked before the old table is replaced. The schema marker is committed in the same transaction.
7. A future schema marker or unknown table shape aborts startup; PlexonHomes never rewrites a newer schema downward.
8. Verify existing names, destinations, world UUID/name, yaw/pitch and home counts before completing runtime certification.

Rollback to v1.0.1 requires both the v1.0.1 JAR and the pre-v2 data/config backup. Do not point v1.0.1 at a migrated schema-v2 database.
