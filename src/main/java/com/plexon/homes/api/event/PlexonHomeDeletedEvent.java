package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeDeletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView home;
    public PlexonHomeDeletedEvent(Player player, HomeView home) { this.player = player; this.home = home; }
    public Player getPlayer() { return player; }
    public HomeView getHome() { return home; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
