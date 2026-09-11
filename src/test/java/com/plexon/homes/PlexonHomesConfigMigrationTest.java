package com.plexon.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PlexonHomesConfigMigrationTest {
    @Test void absentMarkerIsPublishedLegacyV1() {
        assertEquals(1, PlexonHomes.configVersionForMigration(new YamlConfiguration()));
    }

    @Test void acceptsOnlyNumericIntegralOneOrTwo() {
        var yaml = new YamlConfiguration();
        yaml.set("config-version", 1);
        assertEquals(1, PlexonHomes.configVersionForMigration(yaml));
        yaml.set("config-version", 2);
        assertEquals(2, PlexonHomes.configVersionForMigration(yaml));
    }

    @Test void rejectsStringMarkerInsteadOfCoercingIt() {
        var yaml = new YamlConfiguration();
        yaml.set("config-version", "1");
        assertThrows(IllegalArgumentException.class, () -> PlexonHomes.configVersionForMigration(yaml));
    }

    @Test void rejectsFractionalAndFutureMarkers() {
        var yaml = new YamlConfiguration();
        yaml.set("config-version", 1.5D);
        assertThrows(IllegalArgumentException.class, () -> PlexonHomes.configVersionForMigration(yaml));
        yaml.set("config-version", 3);
        assertThrows(IllegalArgumentException.class, () -> PlexonHomes.configVersionForMigration(yaml));
    }
}
