package com.plexon.homes.api.event;

import com.plexon.homes.api.HomeView;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlexonHomeRenamedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final HomeView previous;
    private final HomeView current;
    public PlexonHomeRenamedEvent(Player player, HomeView previous, HomeView current) { this.player = player; this.previous = previous; this.current = current; }
    public Player getPlayer() { return player; }
    public HomeView getPrevious() { return previous; }
    public HomeView getCurrent() { return current; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
