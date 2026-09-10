package com.plexon.homes.config;

import com.plexon.homes.util.HomeNames;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public final class HomesConfig {
    public enum PolicyMode { BLACKLIST, WHITELIST }

    public record WorldPolicy(PolicyMode mode, Set<String> worlds) {
        public boolean allows(World world) {
            if (world == null) return false;
            boolean listed = worlds.contains(world.getName().toLowerCase(Locale.ROOT));
            return mode == PolicyMode.WHITELIST ? listed : !listed;
        }
    }

    public record Snapshot(
            int configVersion,
            String defaultName,
            int maxNameLength,
            boolean allowUseAboveLimit,
            int defaultLimit,
            String permissionPrefix,
            String unlimitedPermission,
            WorldPolicy setWorlds,
            WorldPolicy teleportWorlds,
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
            int deleteConfirmationSeconds,
            String migrationMode) {
        public boolean canSetIn(World world) { return setWorlds.allows(world); }
        public boolean canTeleportTo(World world) { return teleportWorlds.allows(world); }
    }

    private HomesConfig() {}

    public static Snapshot load(FileConfiguration cfg) {
        int version = cfg.getInt("config-version", -1);
        require(version == 2, "config-version must be exactly 2");
        String defaultName = requiredString(cfg, "homes.default-name");
        int maxNameLength = boundedInt(cfg, "homes.max-name-length", 1, 64);
        require(HomeNames.normalize(defaultName, maxNameLength).isPresent(), "homes.default-name is invalid");
        int defaultLimit = boundedInt(cfg, "limits.default", 0, 1000);
        String prefix = requiredString(cfg, "limits.permission-prefix");
        require(prefix.endsWith("."), "limits.permission-prefix must end with '.'");
        String unlimited = requiredString(cfg, "limits.unlimited-permission");
        WorldPolicy setPolicy = policy(cfg, "worlds.set");
        WorldPolicy teleportPolicy = policy(cfg, "worlds.teleport");
        int warmup = boundedInt(cfg, "teleport.warmup-seconds", 0, 3600);
        int cooldown = boundedInt(cfg, "teleport.cooldown-seconds", 0, 86400);
        double movement = finiteNonNegative(cfg, "teleport.movement-threshold");
        double fee = finiteNonNegative(cfg, "teleport.fee");
        int horizontal = boundedInt(cfg, "safe-teleport.horizontal-radius", 0, 8);
        int vertical = boundedInt(cfg, "safe-teleport.vertical-radius", 0, 8);
        int deleteSeconds = boundedInt(cfg, "gui.delete-confirmation-seconds", 3, 120);
        String migrationMode = requiredString(cfg, "migration.mode").toUpperCase(Locale.ROOT);
        require(Set.of("PRIMARY", "SECONDARY").contains(migrationMode), "migration.mode must be PRIMARY or SECONDARY");
        return new Snapshot(version, defaultName, maxNameLength, cfg.getBoolean("homes.allow-use-above-limit", true),
                defaultLimit, prefix, unlimited, setPolicy, teleportPolicy, warmup, cooldown,
                cfg.getBoolean("teleport.cancel-on-movement", true), movement,
                cfg.getBoolean("teleport.cancel-on-damage", true), fee,
                cfg.getBoolean("safe-teleport.enabled", true), cfg.getBoolean("safe-teleport.search-nearby", true),
                horizontal, vertical, deleteSeconds, migrationMode);
    }

    private static WorldPolicy policy(FileConfiguration cfg, String root) {
        String rawMode = requiredString(cfg, root + ".mode").toUpperCase(Locale.ROOT);
        PolicyMode mode;
        try { mode = PolicyMode.valueOf(rawMode); }
        catch (IllegalArgumentException bad) { throw new IllegalArgumentException(root + ".mode must be BLACKLIST or WHITELIST"); }
        List<String> raw = cfg.getStringList(root + ".values");
        Set<String> worlds = new HashSet<>();
        for (String value : raw) {
            require(value != null && !value.isBlank(), root + ".values contains a blank world");
            worlds.add(value.toLowerCase(Locale.ROOT));
        }
        require(mode != PolicyMode.WHITELIST || !worlds.isEmpty(), root + " WHITELIST must contain at least one world");
        return new WorldPolicy(mode, Set.copyOf(worlds));
    }

    private static int boundedInt(FileConfiguration cfg, String path, int min, int max) {
        require(cfg.contains(path), path + " is required");
        int value = cfg.getInt(path);
        require(value >= min && value <= max, path + " must be between " + min + " and " + max);
        return value;
    }

    private static double finiteNonNegative(FileConfiguration cfg, String path) {
        require(cfg.contains(path), path + " is required");
        double value = cfg.getDouble(path);
        require(Double.isFinite(value) && value >= 0.0D, path + " must be finite and non-negative");
        return value;
    }

    private static String requiredString(FileConfiguration cfg, String path) {
        String value = cfg.getString(path);
        require(value != null && !value.isBlank(), path + " is required");
        return value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
