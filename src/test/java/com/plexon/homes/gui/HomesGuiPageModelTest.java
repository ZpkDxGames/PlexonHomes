package com.plexon.homes.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.plexon.homes.model.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomesGuiPageModelTest {
    @Test void firstMiddleAndFinalPagesReferenceExactlyTheirHomes() {
        List<Home> homes = homes(91);

        HomesGui.PageSlice first = HomesGui.pageSlice(homes, 0);
        assertEquals(0, first.page());
        assertEquals(3, first.pages());
        assertEquals(0, first.from());
        assertEquals(45, first.to());
        assertEquals(45, first.refs().size());
        assertEquals(homes.get(0).homeId(), first.refs().get(0).homeId());
        assertEquals(homes.get(44).homeId(), first.refs().get(44).homeId());

        HomesGui.PageSlice middle = HomesGui.pageSlice(homes, 1);
        assertEquals(1, middle.page());
        assertEquals(45, middle.from());
        assertEquals(90, middle.to());
        assertEquals(homes.get(45).homeId(), middle.refs().get(0).homeId());
        assertEquals(homes.get(89).homeId(), middle.refs().get(44).homeId());

        HomesGui.PageSlice last = HomesGui.pageSlice(homes, 2);
        assertEquals(2, last.page());
        assertEquals(90, last.from());
        assertEquals(91, last.to());
        assertEquals(1, last.refs().size());
        assertEquals(homes.get(90).homeId(), last.refs().get(0).homeId());
    }

    @Test void previousAndNextTargetsClampAtRealPageBoundaries() {
        List<Home> homes = homes(91);
        assertEquals(0, HomesGui.pageSlice(homes, -1).page());
        assertEquals(1, HomesGui.pageSlice(homes, 0).page() + 1);
        assertEquals(0, HomesGui.pageSlice(homes, 1).page() - 1);
        assertEquals(2, HomesGui.pageSlice(homes, 99).page());
    }

    @Test void staleSessionGenerationIsRejected() {
        UUID player = UUID.randomUUID();
        assertTrue(HomesGui.sessionMatches(player, player, 12L, 12L));
        assertFalse(HomesGui.sessionMatches(player, player, 13L, 12L));
        assertFalse(HomesGui.sessionMatches(player, UUID.randomUUID(), 12L, 12L));
        assertFalse(HomesGui.sessionMatches(player, player, null, 12L));
    }

    @Test void revisionOrIdentityChangeMakesPageReferenceStale() {
        Home original = home(1, 7L);
        HomesGui.HomeRef ref = new HomesGui.HomeRef(original.homeId(), original.revision());
        assertTrue(HomesGui.matches(ref, original));

        Home renamedOrUpdated = new Home(original.ownerId(), original.homeId(), "renamed", "Renamed",
                original.worldId(), original.worldName(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
                original.createdAt(), original.updatedAt() + 1, original.revision() + 1);
        assertFalse(HomesGui.matches(ref, renamedOrUpdated));

        Home replacement = new Home(original.ownerId(), UUID.randomUUID(), original.nameKey(), original.displayName(),
                original.worldId(), original.worldName(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
                original.createdAt(), original.updatedAt(), original.revision());
        assertFalse(HomesGui.matches(ref, replacement));
    }

    private static List<Home> homes(int count) {
        List<Home> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(home(i, 1L));
        return List.copyOf(out);
    }

    private static Home home(int index, long revision) {
        UUID owner = UUID.nameUUIDFromBytes("owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID world = UUID.nameUUIDFromBytes("world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID homeId = UUID.nameUUIDFromBytes(("home-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Home(owner, homeId, "home" + index, "Home " + index, world, "world",
                index, 64, index, 0, 0, index + 1L, index + 1L, revision);
    }
}
