package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeSetEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView home;
    private final boolean overwrite;
    public PlexonHomeSetEvent(Player player, HomeView home, boolean overwrite) { this.player = player; this.home = home; this.overwrite = overwrite; }
    public Player getPlayer() { return player; }
    public HomeView getHome() { return home; }
    public boolean isOverwrite() { return overwrite; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
