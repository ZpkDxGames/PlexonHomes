package com.plexon.homes.service;

import com.plexon.homes.api.HomeLimitView;
import com.plexon.homes.api.event.PlexonHomeDeletedEvent;
import com.plexon.homes.api.event.PlexonHomeRenamedEvent;
import com.plexon.homes.api.event.PlexonHomeSetEvent;
import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.model.Home;
import com.plexon.homes.persistence.HomeRepository;
import com.plexon.homes.util.HomeNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeService {
    private final JavaPlugin plugin;
    private final HomeRepository repository;
    private final SafeTeleportService safeTeleport;
    private final Supplier<Snapshot> config;
    private final ConcurrentHashMap<UUID, Map<String, Home>> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();

    public HomeService(JavaPlugin plugin, HomeRepository repository, SafeTeleportService safeTeleport, Supplier<Snapshot> config) {
        this.plugin = plugin;
        this.repository = repository;
        this.safeTeleport = safeTeleport;
        this.config = config;
    }

    public CompletableFuture<Void> ensureLoaded(UUID playerId) {
        if (profiles.containsKey(playerId)) return CompletableFuture.completedFuture(null);
        return loading.computeIfAbsent(playerId, id -> repository.loadHomes(id)
                .thenAccept(homes -> {
                    Map<String, Home> map = new LinkedHashMap<>();
                    for (Home home : homes) map.put(home.id(), home);
                    profiles.put(id, Map.copyOf(map));
                })
                .whenComplete((ignored, throwable) -> loading.remove(id)));
    }

    public boolean isLoaded(UUID playerId) { return profiles.containsKey(playerId); }

    public List<Home> listCached(UUID playerId) {
        Map<String, Home> map = profiles.get(playerId);
        if (map == null) return List.of();
        String defaultName = config.get().defaultName().toLowerCase(java.util.Locale.ROOT);
        List<Home> homes = new ArrayList<>(map.values());
        homes.sort(Comparator.comparing((Home h) -> !h.id().equals(defaultName)).thenComparingLong(Home::createdAt));
        return List.copyOf(homes);
    }

    public Optional<Home> findCached(UUID playerId, String name) {
        Map<String, Home> map = profiles.get(playerId);
        if (map == null) return Optional.empty();
        return HomeNames.normalize(name, config.get().maxNameLength()).map(map::get);
    }

    public HomeLimitView limit(Player player) {
        Snapshot snapshot = config.get();
        if (player.hasPermission("plexonhomes.limit.unlimited")) return HomeLimitView.unlimited("permission plexonhomes.limit.unlimited");
        int highest = -1;
        for (int i = 1; i <= 20; i++) if (player.hasPermission("plexonhomes.limit." + i)) highest = i;
        if (highest >= 0) return new HomeLimitView(highest, false, "permission plexonhomes.limit." + highest);
        return new HomeLimitView(snapshot.defaultLimit(), false, "config default");
    }

    public boolean setHomeNow(Player player, String suppliedName) {
        requirePrimary();
        UUID owner = player.getUniqueId();
        Map<String, Home> current = profiles.get(owner);
        if (current == null) return false;
        Snapshot snapshot = config.get();
        String display = suppliedName == null || suppliedName.isBlank() ? snapshot.defaultName() : suppliedName.trim();
        Optional<String> normalized = HomeNames.normalize(display, snapshot.maxNameLength());
        if (normalized.isEmpty()) return false;
        if (!snapshot.canSetIn(player.getWorld())) return false;
        Location location = player.getLocation();
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) return false;
        if (snapshot.safeTeleport() && !safeTeleport.isSafe(location)) return false;

        String id = normalized.get();
        Home previous = current.get(id);
        HomeLimitView limit = limit(player);
        if (previous == null && !limit.unlimited() && current.size() >= limit.limit()) return false;

        long now = System.currentTimeMillis();
        long revision = previous == null ? 1L : previous.revision() + 1L;
        long created = previous == null ? now : previous.createdAt();
        World world = location.getWorld();
        Home home = new Home(owner, id, display, world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), created, now, revision);
        Map<String, Home> next = new LinkedHashMap<>(current);
        next.put(id, home);
        profiles.put(owner, Map.copyOf(next));
        repository.upsert(home).exceptionally(error -> { plugin.getLogger().severe("Failed to persist home " + owner + "/" + id + ": " + error.getMessage()); return null; });
        repository.rememberPlayer(owner, player.getName());
        Bukkit.getPluginManager().callEvent(new PlexonHomeSetEvent(player, home.view(), previous != null));
        return true;
    }

    public boolean deleteHomeNow(Player player, String suppliedName) {
        requirePrimary();
        UUID owner = player.getUniqueId();
        Map<String, Home> current = profiles.get(owner);
        if (current == null) return false;
        Optional<String> normalized = HomeNames.normalize(suppliedName, config.get().maxNameLength());
        if (normalized.isEmpty()) return false;
        Home removed = current.get(normalized.get());
        if (removed == null) return false;
        Map<String, Home> next = new LinkedHashMap<>(current);
        next.remove(normalized.get());
        profiles.put(owner, Map.copyOf(next));
        repository.delete(owner, normalized.get()).exceptionally(error -> { plugin.getLogger().severe("Failed to delete home from SQLite: " + error.getMessage()); return null; });
        Bukkit.getPluginManager().callEvent(new PlexonHomeDeletedEvent(player, removed.view()));
        return true;
    }

    public boolean renameHomeNow(Player player, String oldName, String newName) {
        requirePrimary();
        UUID owner = player.getUniqueId();
        Map<String, Home> current = profiles.get(owner);
        if (current == null) return false;
        Optional<String> oldId = HomeNames.normalize(oldName, config.get().maxNameLength());
        Optional<String> newId = HomeNames.normalize(newName, config.get().maxNameLength());
        if (oldId.isEmpty() || newId.isEmpty() || current.containsKey(newId.get())) return false;
        Home previous = current.get(oldId.get());
        if (previous == null) return false;
        long now = System.currentTimeMillis();
        Home renamed = new Home(previous.ownerId(), newId.get(), newName.trim(), previous.worldId(), previous.worldName(), previous.x(), previous.y(), previous.z(), previous.yaw(), previous.pitch(), previous.createdAt(), now, previous.revision() + 1L);
        Map<String, Home> next = new LinkedHashMap<>(current);
        next.remove(oldId.get()); next.put(newId.get(), renamed);
        profiles.put(owner, Map.copyOf(next));
        repository.rename(previous, renamed).exceptionally(error -> { plugin.getLogger().severe("Failed to rename home in SQLite: " + error.getMessage()); return null; });
        Bukkit.getPluginManager().callEvent(new PlexonHomeRenamedEvent(player, previous.view(), renamed.view()));
        return true;
    }

    public CompletableFuture<Boolean> importHome(Home home, boolean replaceExisting) {
        return ensureLoaded(home.ownerId()).thenCompose(ignored -> onMain(() -> {
            Map<String, Home> current = profiles.getOrDefault(home.ownerId(), Map.of());
            if (!replaceExisting && current.containsKey(home.id())) return false;
            Map<String, Home> next = new LinkedHashMap<>(current);
            next.put(home.id(), home);
            profiles.put(home.ownerId(), Map.copyOf(next));
            repository.upsert(home);
            return true;
        }));
    }

    public void clearProfile(UUID playerId) { profiles.remove(playerId); loading.remove(playerId); }
    public int loadedProfiles() { return profiles.size(); }
    public int cachedHomes() { return profiles.values().stream().mapToInt(Map::size).sum(); }

    private <T> CompletableFuture<T> onMain(java.util.concurrent.Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(callable.call()); }
            catch (Throwable throwable) { future.completeExceptionally(throwable); }
        });
        return future;
    }

    private static void requirePrimary() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Home mutation must run on the primary thread");
    }
}
