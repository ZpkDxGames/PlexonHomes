package com.plexon.homes.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface PlexonHomesAPI {
    List<HomeView> homes(UUID playerId);
    Optional<HomeView> home(UUID playerId, String id);
    int count(UUID playerId);
    HomeLimitView limit(Player player);
    CompletableFuture<Boolean> setHome(Player player, String name);
    CompletableFuture<Boolean> deleteHome(Player player, String name);
    CompletableFuture<Boolean> renameHome(Player player, String oldName, String newName);
    CompletableFuture<HomeTeleportStatus> teleport(Player player, String homeName);
    boolean isTeleportPending(UUID playerId);
}
