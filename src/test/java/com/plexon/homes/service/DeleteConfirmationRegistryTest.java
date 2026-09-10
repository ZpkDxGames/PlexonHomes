package com.plexon.homes.service;

import static org.junit.jupiter.api.Assertions.*;
import com.plexon.homes.model.Home;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeleteConfirmationRegistryTest {
    private static Home home(UUID owner, UUID id, long revision) { return new Home(owner, id, "home", "Home", UUID.randomUUID(), "world", 1, 64, 1, 0, 0, 1, 1, revision); }

    @Test void ticketIsOneShot() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(); Home home = home(actor, UUID.randomUUID(), 4); var ticket = registry.stage(actor, home, Duration.ofSeconds(10), 100);
        assertTrue(registry.consume(actor, ticket.token(), home, 101)); assertFalse(registry.consume(actor, ticket.token(), home, 102));
    }
    @Test void rejectsChangedRevision() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(), id = UUID.randomUUID(); Home original = home(actor, id, 1); var ticket = registry.stage(actor, original, Duration.ofSeconds(10), 100);
        assertFalse(registry.consume(actor, ticket.token(), home(actor, id, 2), 101));
    }
    @Test void rejectsDifferentHome() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(); Home original = home(actor, UUID.randomUUID(), 1); var ticket = registry.stage(actor, original, Duration.ofSeconds(10), 100);
        assertFalse(registry.consume(actor, ticket.token(), home(actor, UUID.randomUUID(), 1), 101));
    }
    @Test void rejectsDifferentActor() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(); Home original = home(actor, UUID.randomUUID(), 1); var ticket = registry.stage(actor, original, Duration.ofSeconds(10), 100);
        assertFalse(registry.consume(UUID.randomUUID(), ticket.token(), original, 101));
    }
    @Test void rejectsExpiredTicket() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(); Home original = home(actor, UUID.randomUUID(), 1); var ticket = registry.stage(actor, original, Duration.ofNanos(5), 100);
        assertFalse(registry.consume(actor, ticket.token(), original, 106));
    }
    @Test void stagingReplacesPriorTicket() {
        var registry = new DeleteConfirmationRegistry(); UUID actor = UUID.randomUUID(); Home first = home(actor, UUID.randomUUID(), 1); Home second = home(actor, UUID.randomUUID(), 1);
        var firstTicket = registry.stage(actor, first, Duration.ofSeconds(10), 100); var secondTicket = registry.stage(actor, second, Duration.ofSeconds(10), 101);
        assertFalse(registry.consume(actor, firstTicket.token(), first, 102)); assertEquals(0, registry.size());
        var thirdTicket = registry.stage(actor, second, Duration.ofSeconds(10), 103); assertTrue(registry.consume(actor, thirdTicket.token(), second, 104)); assertNotEquals(firstTicket.token(), secondTicket.token());
    }
}
