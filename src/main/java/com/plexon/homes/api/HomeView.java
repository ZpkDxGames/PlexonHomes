package com.plexon.homes.api;

import java.util.UUID;

public record HomeView(
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
        long revision) {}
