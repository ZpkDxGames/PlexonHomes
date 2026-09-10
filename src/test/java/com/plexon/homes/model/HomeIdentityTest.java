package com.plexon.homes.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeIdentityTest {
    @Test void legacyIdentityIsDeterministic() { UUID owner = UUID.randomUUID(); assertEquals(HomeIdentity.legacyStableId(owner, "home"), HomeIdentity.legacyStableId(owner, "home")); }
    @Test void differentNamesProduceDifferentIds() { UUID owner = UUID.randomUUID(); assertNotEquals(HomeIdentity.legacyStableId(owner, "home"), HomeIdentity.legacyStableId(owner, "mine")); }
    @Test void differentOwnersProduceDifferentIds() { assertNotEquals(HomeIdentity.legacyStableId(UUID.randomUUID(), "home"), HomeIdentity.legacyStableId(UUID.randomUUID(), "home")); }
    @Test void blankLegacyIdentityIsRejected() { assertThrows(IllegalArgumentException.class, () -> HomeIdentity.legacyStableId(UUID.randomUUID(), " ")); }
    @Test void nullOwnerIsRejected() { assertThrows(IllegalArgumentException.class, () -> HomeIdentity.legacyStableId(null, "home")); }
}
