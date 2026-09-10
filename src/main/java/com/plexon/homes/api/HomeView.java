package com.plexon.homes.api;

import java.util.UUID;

public record HomeView(
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
    /** 1.x source-compatibility alias. Not authoritative identity in 2.x. */
    public String id() { return nameKey; }
}
