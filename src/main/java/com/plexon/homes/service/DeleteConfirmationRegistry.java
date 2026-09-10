package com.plexon.homes.service;

import com.plexon.homes.model.Home;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One bounded delete ticket per actor; tickets are revision-bound, expiring and consumed exactly once. */
public final class DeleteConfirmationRegistry {
    public record Ticket(UUID token, UUID actorId, UUID ownerId, UUID homeId, long revision, long expiresAtNanos) {}
    private final ConcurrentHashMap<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    public Ticket stage(UUID actorId, Home home, Duration ttl) { return stage(actorId, home, ttl, System.nanoTime()); }
    Ticket stage(UUID actorId, Home home, Duration ttl, long now) {
        if (actorId == null || home == null || ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Invalid delete confirmation");
        long ttlNanos = ttl.toNanos(); long expires = now > Long.MAX_VALUE - ttlNanos ? Long.MAX_VALUE : now + ttlNanos;
        Ticket ticket = new Ticket(UUID.randomUUID(), actorId, home.ownerId(), home.homeId(), home.revision(), expires); tickets.put(actorId, ticket); return ticket;
    }

    public boolean consume(UUID actorId, UUID token, Home current) { return consume(actorId, token, current, System.nanoTime()); }
    boolean consume(UUID actorId, UUID token, Home current, long now) {
        Ticket ticket = tickets.remove(actorId);
        return ticket != null && ticket.token().equals(token) && now <= ticket.expiresAtNanos() && current != null
                && ticket.actorId().equals(actorId) && ticket.ownerId().equals(current.ownerId())
                && ticket.homeId().equals(current.homeId()) && ticket.revision() == current.revision();
    }

    public void clear(UUID actorId) { tickets.remove(actorId); }
    public void clearAll() { tickets.clear(); }
    public int size() { return tickets.size(); }
}
