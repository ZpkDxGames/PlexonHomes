package com.plexon.homes.listener;

import com.plexon.homes.persistence.HomeRepository;
import com.plexon.homes.service.HomeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final HomeService homes;
    private final HomeRepository repository;
    public PlayerLifecycleListener(HomeService homes, HomeRepository repository) { this.homes = homes; this.repository = repository; }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        homes.ensureLoaded(event.getPlayer().getUniqueId());
        repository.rememberPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        homes.clearProfile(event.getPlayer().getUniqueId());
    }
}
