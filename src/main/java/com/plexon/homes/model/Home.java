package com.plexon.homes.model;

import com.plexon.homes.api.HomeView;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

public record Home(
        UUID ownerId,
        String id,
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

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public HomeView view() {
        return new HomeView(ownerId, id, displayName, worldId, worldName, x, y, z, yaw, pitch, createdAt, updatedAt, revision);
    }
}
