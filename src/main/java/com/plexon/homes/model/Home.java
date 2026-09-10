package com.plexon.homes.model;

import com.plexon.homes.api.HomeView;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

/** Immutable authoritative home state. homeId is stable; nameKey is user-facing lookup identity. */
public record Home(
        UUID ownerId,
        UUID homeId,
        String nameKey,
        String displayName,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long createdAt,
        long updatedAt,
        long revision) {

    /** Compatibility alias for 1.x callers. This is a mutable name key, not authoritative identity. */
    public String id() { return nameKey; }

    public Location toLocation(World world) { return new Location(world, x, y, z, yaw, pitch); }

    public Home withName(String newNameKey, String newDisplayName, long now) {
        return new Home(ownerId, homeId, newNameKey, newDisplayName, worldId, worldName, x, y, z, yaw, pitch,
                createdAt, now, revision + 1L);
    }

    public Home withLocation(Location location, long now) {
        World world = location.getWorld();
        if (world == null) throw new IllegalArgumentException("Home location world is required");
        return new Home(ownerId, homeId, nameKey, displayName, world.getUID(), world.getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                createdAt, now, revision + 1L);
    }

    public HomeView view() {
        return new HomeView(ownerId, homeId, nameKey, displayName, worldId, worldName, x, y, z, yaw, pitch,
                createdAt, updatedAt, revision);
    }
}
