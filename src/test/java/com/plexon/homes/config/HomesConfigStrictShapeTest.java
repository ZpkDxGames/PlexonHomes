package com.plexon.homes.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HomesConfigStrictShapeTest {
    private static YamlConfiguration valid() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
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
                  set: {mode: BLACKLIST, values: [lobby]}
                  teleport: {mode: BLACKLIST, values: []}
                teleport:
                  warmup-seconds: 3
                  cooldown-seconds: 10
                  cancel-on-movement: true
                  movement-threshold: 0.01
                  cancel-on-damage: true
                  fee: 0.0
                safe-teleport:
                  enabled: true
                  search-nearby: true
                  horizontal-radius: 3
                  vertical-radius: 4
                gui:
                  delete-confirmation-seconds: 15
                migration:
                  mode: PRIMARY
                """);
        return yaml;
    }

    @Test void rejectsObjectInsideWorldNameList() throws Exception {
        var yaml = valid();
        yaml.set("worlds.set.values", java.util.List.of(Map.of("name", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(yaml));
    }

    @Test void rejectsWrongTypedLimitTimingAndCancellationSettings() throws Exception {
        var limit = valid(); limit.set("limits.default", true);
        assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(limit));
        var timing = valid(); timing.set("teleport.cooldown-seconds", 1.5D);
        assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(timing));
        var cancellation = valid(); cancellation.set("teleport.cancel-on-movement", 1);
        assertThrows(IllegalArgumentException.class, () -> HomesConfig.load(cancellation));
    }
}
