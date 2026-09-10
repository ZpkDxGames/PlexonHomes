package com.plexon.homes.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class HomeIdentity {
    private HomeIdentity() {}

    /** Deterministic identity used only when upgrading legacy 1.x name-keyed rows. */
    public static UUID legacyStableId(UUID ownerId, String legacyNameKey) {
        if (ownerId == null || legacyNameKey == null || legacyNameKey.isBlank()) throw new IllegalArgumentException("Legacy home identity is incomplete");
        return UUID.nameUUIDFromBytes(("plexonhomes:v1:" + ownerId + ":" + legacyNameKey).getBytes(StandardCharsets.UTF_8));
    }
}
