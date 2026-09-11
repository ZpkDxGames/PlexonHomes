# Changelog

## 2.0.0 — Stable

- Promotes the accepted Phase 2 / Phase 3 homes runtime line without changing production Java during stable closure.
- Adds stable home UUID identity, SQLite schema v2, persistence-first mutations and stale async-load rejection.
- Preserves authoritative duplicate-safe teleport attempts, bounded safe-destination validation, successful-only cooldown commit and at-most-once fee compensation.
- Preserves transactional configuration reload, revision-bound GUI deletion and cache-only PlaceholderAPI state.
- Includes the dedicated SetHome 6.3 migration provider with read-only scan/plan/verify/status, fingerprint-bound execution, deterministic import identity and conflict/quarantine reporting.
- Keeps SetHome migration execution and decommission operator-gated; stable GitHub publication does not imply production cutover approval.
- Replaces RC/one-off publishers with version-dynamic Build verification and exact-current-`main` stable publication including downloaded-release checksum/provenance verification.

## 1.0.0 — 2026-09-09

- Core-native `/sethome`, `/home`, `/homes`, `/delhome`, and `/renamehome`.
- Permission/rank-compatible home limits.
- SQLite WAL persistence with async reads/writes.
- Shared PlexonCore player-watch warmup cancellation.
- Safe teleport checks with bounded nearby fallback and async chunk loading.
- Cooldowns and optional Vault-backed teleport fees with refund on teleport failure.
- Paginated home GUI and management actions.
- Public API/events, diagnostics, backup and Essentials home migration workflow.
