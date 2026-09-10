package com.plexon.homes.api;

import com.plexon.homes.model.Home;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public final class DefaultPlexonHomesAPI implements PlexonHomesAPI {
    private final HomeService homes; private final TeleportService teleports;
    public DefaultPlexonHomesAPI(HomeService homes, TeleportService teleports) { this.homes = homes; this.teleports = teleports; }
    @Override public List<HomeView> homes(UUID playerId) { return homes.listCached(playerId).stream().map(Home::view).toList(); }
    @Override public Optional<HomeView> home(UUID playerId, String name) { return homes.findCached(playerId, name).map(Home::view); }
    @Override public Optional<HomeView> homeById(UUID playerId, UUID homeId) { return homes.findByIdCached(playerId, homeId).map(Home::view); }
    @Override public int count(UUID playerId) { return homes.listCached(playerId).size(); }
    @Override public HomeLimitView limit(Player player) { return homes.limit(player); }
    @Override public CompletableFuture<Boolean> setHome(Player player, String name) { return homes.setHome(player, name); }
    @Override public CompletableFuture<Boolean> deleteHome(Player player, String name) { return homes.deleteHome(player, name); }
    @Override public CompletableFuture<Boolean> renameHome(Player player, String oldName, String newName) { return homes.renameHome(player, oldName, newName); }
    @Override public CompletableFuture<Boolean> updateHomeLocation(Player player, UUID homeId, long expectedRevision) { return homes.updateHomeLocation(player, homeId, expectedRevision); }
    @Override public CompletableFuture<HomeTeleportStatus> teleport(Player player, String homeName) { return teleports.request(player, homeName); }
    @Override public boolean isTeleportPending(UUID playerId) { return teleports.isPending(playerId); }
}
