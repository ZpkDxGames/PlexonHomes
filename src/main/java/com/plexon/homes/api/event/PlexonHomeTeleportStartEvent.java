package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeTeleportStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView home;
    private final Location destination;
    private boolean cancelled;
    public PlexonHomeTeleportStartEvent(Player player, HomeView home, Location destination) { this.player = player; this.home = home; this.destination = destination.clone(); }
    public Player getPlayer() { return player; }
    public HomeView getHome() { return home; }
    public Location getDestination() { return destination.clone(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
