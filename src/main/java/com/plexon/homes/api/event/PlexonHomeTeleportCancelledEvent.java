package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeTeleportCancelledEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView home;
    private final HomeTeleportCancellationReason reason;
    public PlexonHomeTeleportCancelledEvent(Player player, HomeView home, HomeTeleportCancellationReason reason) { this.player = player; this.home = home; this.reason = reason; }
    public Player getPlayer() { return player; }
    public HomeView getHome() { return home; }
    public HomeTeleportCancellationReason getReason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
