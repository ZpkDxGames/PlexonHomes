package com.plexon.homes.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface PlexonHomesAPI {
    List<HomeView> homes(UUID playerId);
    Optional<HomeView> home(UUID playerId, String name);
    Optional<HomeView> homeById(UUID playerId, UUID homeId);
    int count(UUID playerId);
    HomeLimitView limit(Player player);
    CompletableFuture<Boolean> setHome(Player player, String name);
    CompletableFuture<Boolean> deleteHome(Player player, String name);
    CompletableFuture<Boolean> renameHome(Player player, String oldName, String newName);
    CompletableFuture<Boolean> updateHomeLocation(Player player, UUID homeId, long expectedRevision);
    CompletableFuture<HomeTeleportStatus> teleport(Player player, String homeName);
    boolean isTeleportPending(UUID playerId);
}
