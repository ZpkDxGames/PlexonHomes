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
        int version = boundedInt(cfg, "config-version", 2, 2);
        String defaultName = requiredString(cfg, "homes.default-name");
        int maxNameLength = boundedInt(cfg, "homes.max-name-length", 1, 64);
        require(HomeNames.normalize(defaultName, maxNameLength).isPresent(), "homes.default-name is invalid");
        boolean allowAbove = requiredBoolean(cfg, "homes.allow-use-above-limit");
        int defaultLimit = boundedInt(cfg, "limits.default", 0, 1000);
        String prefix = requiredString(cfg, "limits.permission-prefix");
        require(prefix.endsWith("."), "limits.permission-prefix must end with '.'");
        String unlimited = requiredString(cfg, "limits.unlimited-permission");
        WorldPolicy setPolicy = policy(cfg, "worlds.set");
        WorldPolicy teleportPolicy = policy(cfg, "worlds.teleport");
        int warmup = boundedInt(cfg, "teleport.warmup-seconds", 0, 3600);
        int cooldown = boundedInt(cfg, "teleport.cooldown-seconds", 0, 86400);
        boolean cancelMove = requiredBoolean(cfg, "teleport.cancel-on-movement");
        double movement = finiteNonNegative(cfg, "teleport.movement-threshold");
        boolean cancelDamage = requiredBoolean(cfg, "teleport.cancel-on-damage");
        double fee = finiteNonNegative(cfg, "teleport.fee");
        boolean safe = requiredBoolean(cfg, "safe-teleport.enabled");
        boolean nearby = requiredBoolean(cfg, "safe-teleport.search-nearby");
        int horizontal = boundedInt(cfg, "safe-teleport.horizontal-radius", 0, 8);
        int vertical = boundedInt(cfg, "safe-teleport.vertical-radius", 0, 8);
        int deleteSeconds = boundedInt(cfg, "gui.delete-confirmation-seconds", 3, 120);
        String migrationMode = requiredString(cfg, "migration.mode").toUpperCase(Locale.ROOT);
        require(Set.of("PRIMARY", "SECONDARY").contains(migrationMode), "migration.mode must be PRIMARY or SECONDARY");
        return new Snapshot(version, defaultName, maxNameLength, allowAbove, defaultLimit, prefix, unlimited,
                setPolicy, teleportPolicy, warmup, cooldown, cancelMove, movement, cancelDamage, fee,
                safe, nearby, horizontal, vertical, deleteSeconds, migrationMode);
    }

    private static WorldPolicy policy(FileConfiguration cfg, String root) {
        String rawMode = requiredString(cfg, root + ".mode").toUpperCase(Locale.ROOT);
        PolicyMode mode;
        try { mode = PolicyMode.valueOf(rawMode); }
        catch (IllegalArgumentException bad) { throw new IllegalArgumentException(root + ".mode must be BLACKLIST or WHITELIST"); }
        Object rawValues = cfg.get(root + ".values");
        require(rawValues instanceof List<?>, root + ".values must be a YAML list");
        Set<String> worlds = new HashSet<>();
        for (Object raw : (List<?>) rawValues) {
            require(raw instanceof String, root + ".values may contain only world names");
            String value = ((String) raw).trim();
            require(!value.isBlank(), root + ".values contains a blank world");
            worlds.add(value.toLowerCase(Locale.ROOT));
        }
        require(mode != PolicyMode.WHITELIST || !worlds.isEmpty(), root + " WHITELIST must contain at least one world");
        return new WorldPolicy(mode, Set.copyOf(worlds));
    }

    private static int boundedInt(FileConfiguration cfg, String path, int min, int max) {
        Object raw = cfg.get(path);
        require(raw instanceof Number, path + " must be an integer");
        double numeric = ((Number) raw).doubleValue();
        require(Double.isFinite(numeric) && numeric == Math.rint(numeric), path + " must be an integer");
        require(numeric >= min && numeric <= max, path + " must be between " + min + " and " + max);
        return (int) numeric;
    }

    private static double finiteNonNegative(FileConfiguration cfg, String path) {
        Object raw = cfg.get(path);
        require(raw instanceof Number, path + " must be numeric");
        double value = ((Number) raw).doubleValue();
        require(Double.isFinite(value) && value >= 0.0D, path + " must be finite and non-negative");
        return value;
    }

    private static boolean requiredBoolean(FileConfiguration cfg, String path) {
        Object raw = cfg.get(path); require(raw instanceof Boolean, path + " must be true or false"); return (Boolean) raw;
    }

    private static String requiredString(FileConfiguration cfg, String path) {
        Object raw = cfg.get(path); require(raw instanceof String && !((String) raw).isBlank(), path + " is required and must be text"); return ((String) raw).trim();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
