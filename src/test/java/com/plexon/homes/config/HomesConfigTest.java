package com.plexon.homes.config;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HomesConfigTest {
    private static final String VALID = """
            config-version: 2
            homes:
              default-name: home
              max-name-length: 24
              allow-use-above-limit: true
            limits:
              default: 3
              permission-prefix: plexonhomes.limit.
              unlimited-permission: plexonhomes.limit.unlimited
            worlds:
              set:
                mode: BLACKLIST
                values: [lobby]
              teleport:
                mode: BLACKLIST
                values: []
            teleport:
              warmup-seconds: 3
              cooldown-seconds: 10
              cancel-on-movement: true
              movement-threshold: 0.01
              cancel-on-damage: true
              fee: 2.5
            safe-teleport:
              enabled: true
              search-nearby: true
              horizontal-radius: 3
              vertical-radius: 4
            gui:
              delete-confirmation-seconds: 15
            migration:
              mode: PRIMARY
            """;

    private static YamlConfiguration config() throws Exception { var yaml = new YamlConfiguration(); yaml.loadFromString(VALID); return yaml; }

    @Test void acceptsPublishedPhase2Shape() throws Exception { var snapshot = HomesConfig.load(config()); assertEquals(2, snapshot.configVersion()); assertEquals(3, snapshot.defaultLimit()); assertEquals(2.5D, snapshot.fee()); }
    @Test void rejectsMissingVersion() throws Exception { var yaml = config(); yaml.set("config-version", null); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsFutureVersion() throws Exception { var yaml = config(); yaml.set("config-version", 3); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsNegativeFeeInsteadOfClamping() throws Exception { var yaml = config(); yaml.set("teleport.fee", -1); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsNegativeWarmupInsteadOfClamping() throws Exception { var yaml = config(); yaml.set("teleport.warmup-seconds", -1); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsOversizedSafetyRadiusInsteadOfClamping() throws Exception { var yaml = config(); yaml.set("safe-teleport.horizontal-radius", 9); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsInvalidWorldPolicyMode() throws Exception { var yaml = config(); yaml.set("worlds.set.mode", "MAGIC"); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsEmptyWhitelist() throws Exception { var yaml = config(); yaml.set("worlds.teleport.mode", "WHITELIST"); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void acceptsNonEmptyWhitelist() throws Exception { var yaml = config(); yaml.set("worlds.teleport.mode", "WHITELIST"); yaml.set("worlds.teleport.values", java.util.List.of("survival")); assertEquals(HomesConfig.PolicyMode.WHITELIST, HomesConfig.load(yaml).teleportWorlds().mode()); }
    @Test void rejectsPermissionPrefixWithoutDot() throws Exception { var yaml = config(); yaml.set("limits.permission-prefix", "plexonhomes.limit"); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsInvalidDefaultHomeName() throws Exception { var yaml = config(); yaml.set("homes.default-name", "my home"); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
    @Test void rejectsInvalidMigrationMode() throws Exception { var yaml = config(); yaml.set("migration.mode", "UNKNOWN"); assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml)); }
}
