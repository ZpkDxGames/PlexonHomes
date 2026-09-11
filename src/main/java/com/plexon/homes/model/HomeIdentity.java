package com.plexon.homes.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public final class HomeIdentity {
    private HomeIdentity() {}

    /** Deterministic identity used only when upgrading legacy 1.x name-keyed rows. */
    public static UUID legacyStableId(UUID ownerId, String legacyNameKey) {
        if (ownerId == null || legacyNameKey == null || legacyNameKey.isBlank()) throw new IllegalArgumentException("Legacy home identity is incomplete");
        return UUID.nameUUIDFromBytes(("plexonhomes:v1:" + ownerId + ":" + legacyNameKey).getBytes(StandardCharsets.UTF_8));
    }

    /** Deterministic identity for one record from an external migration provider. */
    public static UUID migrationStableId(UUID ownerId, String provider, String sourceKey) {
        if (ownerId == null || provider == null || provider.isBlank() || sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("Migration home identity is incomplete");
        }
        String providerKey = provider.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("plexonhomes:migration:v1:" + providerKey + ":" + ownerId + ":" + sourceKey)
                .getBytes(StandardCharsets.UTF_8));
    }
}
