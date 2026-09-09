package com.plexon.homes.api;

import com.plexon.homes.model.Home;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultPlexonHomesAPI implements PlexonHomesAPI {
    private final JavaPlugin plugin;
    private final HomeService homes;
    private final TeleportService teleports;

    public DefaultPlexonHomesAPI(JavaPlugin plugin, HomeService homes, TeleportService teleports) {
        this.plugin = plugin;
        this.homes = homes;
        this.teleports = teleports;
    }

    @Override public List<HomeView> homes(UUID playerId) { return homes.listCached(playerId).stream().map(Home::view).toList(); }
    @Override public Optional<HomeView> home(UUID playerId, String id) { return homes.findCached(playerId, id).map(Home::view); }
    @Override public int count(UUID playerId) { return homes.listCached(playerId).size(); }
    @Override public HomeLimitView limit(Player player) { return homes.limit(player); }
    @Override public CompletableFuture<Boolean> setHome(Player player, String name) { return mutate(player, () -> homes.setHomeNow(player, name)); }
    @Override public CompletableFuture<Boolean> deleteHome(Player player, String name) { return mutate(player, () -> homes.deleteHomeNow(player, name)); }
    @Override public CompletableFuture<Boolean> renameHome(Player player, String oldName, String newName) { return mutate(player, () -> homes.renameHomeNow(player, oldName, newName)); }
    @Override public CompletableFuture<HomeTeleportStatus> teleport(Player player, String homeName) { return teleports.request(player, homeName); }
    @Override public boolean isTeleportPending(UUID playerId) { return teleports.isPending(playerId); }

    private CompletableFuture<Boolean> mutate(Player player, java.util.concurrent.Callable<Boolean> mutation) {
        return homes.ensureLoaded(player.getUniqueId()).thenCompose(ignored -> {
            if (Bukkit.isPrimaryThread()) {
                try { return CompletableFuture.completedFuture(mutation.call()); }
                catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
            }
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { result.complete(mutation.call()); }
                catch (Throwable throwable) { result.completeExceptionally(throwable); }
            });
            return result;
        });
    }
}
