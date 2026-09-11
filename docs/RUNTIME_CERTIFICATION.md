# PlexonCraft runtime certification — PlexonHomes 2.0.0

Current deployment evidence state: **NOT EXECUTED**.

This matrix validates the exact published stable artifact on PlexonCraft. Repository/source/release closure is established independently by GitHub CI and the exact-`main` stable publisher; these live checks are operational follow-up evidence and do not block the verified stable release.

Validate:

- representative v1.0.1 database/config migration and existing-home preservation
- `/sethome`, `/home`, `/homes`, multiple homes and deterministic limit permissions
- rename preserving stable UUID; update-location preserving UUID; deletion and stale confirmation rejection
- missing world, malformed/non-finite destination and unsafe destination behavior
- warmup, cooldown, movement cancellation, damage cancellation, logout/death/world-change cancellation
- duplicate request rejection during resolve/warmup and while `teleportAsync` is in flight
- stale async callback ownership and plugin-disable cleanup
- Vault/Theosis exact charge, insufficient funds, failed teleport refund and no duplicate charge/refund when fees are enabled
- full restart persistence, rapid reconnect/stale load rejection and offline admin inspection
- valid reload and invalid reload retaining the previous runtime snapshot
- PlaceholderAPI cache-only behavior and public API/event timing
- PlexonCore READY state and shared player-watch registration
- protection-plugin interoperability through normal Bukkit/Paper teleport event behavior
- SetHome scan/plan/execute/verify under an explicitly approved migration window, including source fingerprint and idempotency
- SetHome home-count/name/world/location precision and `limits.default: 15` policy validation
- rollback rehearsal while original SetHome source data remains preserved
- Spark/MSPT comparison against v1.0.1 under representative home use
- at least 30 minutes soak
- zero HIGH/CRITICAL defects

Record the exact stable tag/SHA, JAR SHA-256, server/Paper/Core builds, migration backup paths, test evidence and Spark comparison when this matrix is executed.
