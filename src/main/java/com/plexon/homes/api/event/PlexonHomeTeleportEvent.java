package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeTeleportEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView home;
    private final Location from;
    private final Location to;
    private final double feeCharged;
    public PlexonHomeTeleportEvent(Player player, HomeView home, Location from, Location to, double feeCharged) {
        this.player = player; this.home = home; this.from = from.clone(); this.to = to.clone(); this.feeCharged = feeCharged;
    }
    public Player getPlayer() { return player; }
    public HomeView getHome() { return home; }
    public Location getFrom() { return from.clone(); }
    public Location getTo() { return to.clone(); }
    public double getFeeCharged() { return feeCharged; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
