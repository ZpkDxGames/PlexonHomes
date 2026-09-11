#!/usr/bin/env bash
set -euo pipefail
JAR="${1:-target/PlexonHomes-2.0.0.jar}"
EXPECTED_VERSION="${2:-}"
[[ -f "$JAR" ]] || { echo "missing jar: $JAR" >&2; exit 1; }
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT; jar tf "$JAR" > "$LIST"
for required in plugin.yml config.yml gui.yml messages.yml migration.yml com/plexon/homes/PlexonHomes.class com/plexon/homes/api/PlexonHomesAPI.class com/plexon/homes/model/Home.class com/plexon/homes/model/HomeIdentity.class com/plexon/homes/service/TeleportService.class com/plexon/homes/service/DeleteConfirmationRegistry.class com/plexon/homes/integration/HomesPlaceholderExpansion.class com/plexon/homes/migration/SetHomeMigrationService.class org/sqlite/JDBC.class; do
  grep -qx "$required" "$LIST" || { echo "missing $required" >&2; exit 1; }
done
for prefix in '^com/zpkdxgames/plexoncore/' '^org/bukkit/' '^io/papermc/' '^net/kyori/adventure/' '^me/clip/placeholderapi/' '^net/milkbowl/vault/'; do
  if grep -q "$prefix" "$LIST"; then echo "provided dependency shaded unexpectedly: $prefix" >&2; exit 1; fi
done
if [[ -n "$EXPECTED_VERSION" ]]; then
  actual="$(unzip -p "$JAR" plugin.yml | sed -n 's/^version:[[:space:]]*//p' | head -n1 | tr -d " '\"\r")"
  [[ "$actual" == "$EXPECTED_VERSION" ]] || { echo "plugin version mismatch: expected $EXPECTED_VERSION got $actual" >&2; exit 1; }
fi
unzip -p "$JAR" config.yml | grep -F '  default: 15' >/dev/null
javap -verbose -classpath "$JAR" com.plexon.homes.PlexonHomes | grep -q 'major version: 69' || { echo 'expected Java 25 class major 69' >&2; exit 1; }
printf 'distribution verification passed: %s\n' "$JAR"
