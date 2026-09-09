package com.plexon.homes.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public final class HomesConfig {
    public record Snapshot(
            String defaultName,
            int maxNameLength,
            boolean allowUseAboveLimit,
            int defaultLimit,
            Set<String> blockedSetWorlds,
            Set<String> blockedTeleportWorlds,
            int warmupSeconds,
            int cooldownSeconds,
            boolean cancelOnMovement,
            double movementThreshold,
            boolean cancelOnDamage,
            double fee,
            boolean safeTeleport,
            boolean searchNearby,
            int horizontalRadius,
            int verticalRadius,
            String migrationMode) {

        public boolean canSetIn(World world) { return !blockedSetWorlds.contains(world.getName().toLowerCase(Locale.ROOT)); }
        public boolean canTeleportTo(World world) { return !blockedTeleportWorlds.contains(world.getName().toLowerCase(Locale.ROOT)); }
    }

    private HomesConfig() {}

    public static Snapshot load(FileConfiguration cfg) {
        return new Snapshot(
                cfg.getString("homes.default-name", "home"),
                Math.max(1, cfg.getInt("homes.max-name-length", 24)),
                cfg.getBoolean("homes.allow-use-above-limit", true),
                Math.max(0, cfg.getInt("limits.default", 1)),
                lowerSet(cfg.getStringList("worlds.set.values")),
                lowerSet(cfg.getStringList("worlds.teleport.values")),
                Math.max(0, cfg.getInt("teleport.warmup-seconds", 3)),
                Math.max(0, cfg.getInt("teleport.cooldown-seconds", 10)),
                cfg.getBoolean("teleport.cancel-on-movement", true),
                Math.max(0.0D, cfg.getDouble("teleport.movement-threshold", 0.01D)),
                cfg.getBoolean("teleport.cancel-on-damage", true),
                Math.max(0.0D, cfg.getDouble("teleport.fee", 0.0D)),
                cfg.getBoolean("safe-teleport.enabled", true),
                cfg.getBoolean("safe-teleport.search-nearby", true),
                Math.max(0, Math.min(8, cfg.getInt("safe-teleport.horizontal-radius", 3))),
                Math.max(0, Math.min(8, cfg.getInt("safe-teleport.vertical-radius", 4))),
                cfg.getString("migration.mode", "PRIMARY").toUpperCase(Locale.ROOT));
    }

    private static Set<String> lowerSet(java.util.List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) if (value != null) out.add(value.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }
}
