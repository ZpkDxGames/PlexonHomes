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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeService {
    private final JavaPlugin plugin;
    private final HomeRepository repository;
    private final SafeTeleportService safeTeleport;
    private final Supplier<Snapshot> config;
    private final ConcurrentHashMap<UUID, Map<String, Home>> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> loadGenerations = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> mutating = ConcurrentHashMap.newKeySet();

    public HomeService(JavaPlugin plugin, HomeRepository repository, SafeTeleportService safeTeleport, Supplier<Snapshot> config) {
        this.plugin = plugin; this.repository = repository; this.safeTeleport = safeTeleport; this.config = config;
    }

    public CompletableFuture<Void> ensureLoaded(UUID playerId) {
        if (profiles.containsKey(playerId)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> existing = loading.get(playerId);
        if (existing != null) return existing;
        long generation = loadGenerations.computeIfAbsent(playerId, ignored -> new AtomicLong()).get();
        CompletableFuture<Void> created = repository.loadHomes(playerId).thenAccept(homes -> {
            if (loadGenerations.computeIfAbsent(playerId, ignored -> new AtomicLong()).get() != generation) return;
            Map<String, Home> map = new LinkedHashMap<>();
            for (Home home : homes) {
                Home previous = map.put(home.nameKey(), home);
                if (previous != null) throw new IllegalStateException("Duplicate cached home name for " + playerId + ": " + home.nameKey());
            }
            profiles.put(playerId, Map.copyOf(map));
        });
        CompletableFuture<Void> raced = loading.putIfAbsent(playerId, created);
        if (raced != null) return raced;
        created.whenComplete((ignored, error) -> loading.remove(playerId, created));
        return created;
    }

    public boolean isLoaded(UUID playerId) { return profiles.containsKey(playerId); }

    public List<Home> listCached(UUID playerId) {
        Map<String, Home> map = profiles.get(playerId);
        if (map == null) return List.of();
        String defaultName = HomeNames.normalize(config.get().defaultName(), config.get().maxNameLength()).orElse("home");
        List<Home> homes = new ArrayList<>(map.values());
        homes.sort(Comparator.comparing((Home h) -> !h.nameKey().equals(defaultName)).thenComparingLong(Home::createdAt));
        return List.copyOf(homes);
    }

    public Optional<Home> findCached(UUID playerId, String name) {
        Map<String, Home> map = profiles.get(playerId);
        if (map == null) return Optional.empty();
        return HomeNames.normalize(name, config.get().maxNameLength()).map(map::get);
    }

    public Optional<Home> findByIdCached(UUID playerId, UUID homeId) {
        if (homeId == null) return Optional.empty();
        Map<String, Home> map = profiles.get(playerId);
        if (map == null) return Optional.empty();
        return map.values().stream().filter(home -> home.homeId().equals(homeId)).findFirst();
    }

    public HomeLimitView limit(Player player) {
        Snapshot snapshot = config.get();
        if (player.hasPermission(snapshot.unlimitedPermission())) return HomeLimitView.unlimited("permission " + snapshot.unlimitedPermission());
        int highest = -1;
        String prefix = snapshot.permissionPrefix().toLowerCase(java.util.Locale.ROOT);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String permission = info.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (!permission.startsWith(prefix)) continue;
            String suffix = permission.substring(prefix.length());
            try { highest = Math.max(highest, Integer.parseInt(suffix)); }
            catch (NumberFormatException ignored) { }
        }
        if (highest >= 0) return new HomeLimitView(highest, false, "permission " + snapshot.permissionPrefix() + highest);
        return new HomeLimitView(snapshot.defaultLimit(), false, "config default");
    }

    static boolean canCreateAtLimit(int currentCount, HomeLimitView limit) {
        return limit.unlimited() || currentCount < limit.limit();
    }

    public CompletableFuture<Boolean> setHome(Player player, String suppliedName) {
        if (player == null || !player.hasPermission("plexonhomes.sethome")) return CompletableFuture.completedFuture(false);
        UUID owner = player.getUniqueId();
        return ensureLoaded(owner).thenCompose(ignored -> onMain(() -> prepareSet(player, suppliedName))).thenCompose(plan -> {
            if (plan == null) return CompletableFuture.completedFuture(false);
            return repository.upsert(plan.home).thenCompose(ignored -> onMain(() -> {
                Map<String, Home> current = profiles.get(owner);
                if (current != null) {
                    Map<String, Home> next = new LinkedHashMap<>(current); next.put(plan.home.nameKey(), plan.home); profiles.put(owner, Map.copyOf(next));
                }
                repository.rememberPlayer(owner, player.getName());
                Bukkit.getPluginManager().callEvent(new PlexonHomeSetEvent(player, plan.home.view(), plan.previous != null));
                return true;
            })).whenComplete((ok, error) -> mutating.remove(owner));
        }).exceptionally(error -> { mutating.remove(owner); plugin.getLogger().severe("Failed to persist home for " + owner + ": " + rootMessage(error)); return false; });
    }

    private SetPlan prepareSet(Player player, String suppliedName) {
        requirePrimary();
        UUID owner = player.getUniqueId();
        if (!mutating.add(owner)) return null;
        Map<String, Home> current = profiles.get(owner);
        if (current == null) { mutating.remove(owner); return null; }
        Snapshot snapshot = config.get();
        String display = suppliedName == null || suppliedName.isBlank() ? snapshot.defaultName() : suppliedName.trim();
        Optional<String> normalized = HomeNames.normalize(display, snapshot.maxNameLength());
        Location location = player.getLocation();
        if (normalized.isEmpty() || !snapshot.canSetIn(player.getWorld()) || !validLocation(location) || (snapshot.safeTeleport() && !safeTeleport.isSafe(location))) {
            mutating.remove(owner); return null;
        }
        String nameKey = normalized.get();
        Home previous = current.get(nameKey);
        HomeLimitView limit = limit(player);
        if (previous == null && !canCreateAtLimit(current.size(), limit)) { mutating.remove(owner); return null; }
        long now = System.currentTimeMillis();
        World world = location.getWorld();
        Home home = previous == null
                ? new Home(owner, UUID.randomUUID(), nameKey, display, world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), now, now, 1L)
                : new Home(owner, previous.homeId(), nameKey, display, world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), previous.createdAt(), now, previous.revision() + 1L);
        return new SetPlan(previous, home);
    }

    public CompletableFuture<Boolean> deleteHome(Player player, String suppliedName) {
        if (player == null || !player.hasPermission("plexonhomes.delete")) return CompletableFuture.completedFuture(false);
        return ensureLoaded(player.getUniqueId()).thenCompose(ignored -> onMain(() -> findCached(player.getUniqueId(), suppliedName).orElse(null)))
                .thenCompose(home -> home == null ? CompletableFuture.completedFuture(false) : deleteHome(player, home.homeId(), home.revision()));
    }

    public CompletableFuture<Boolean> deleteHome(Player player, UUID homeId, long expectedRevision) {
        if (player == null || !player.hasPermission("plexonhomes.delete")) return CompletableFuture.completedFuture(false);
        UUID owner = player.getUniqueId();
        return ensureLoaded(owner).thenCompose(ignored -> onMain(() -> {
            if (!mutating.add(owner)) return null;
            Home current = findByIdCached(owner, homeId).orElse(null);
            if (current == null || current.revision() != expectedRevision) { mutating.remove(owner); return null; }
            return current;
        })).thenCompose(home -> {
            if (home == null) return CompletableFuture.completedFuture(false);
            return repository.delete(owner, home.homeId()).thenCompose(ignored -> onMain(() -> {
                Map<String, Home> current = profiles.get(owner);
                if (current != null) { Map<String, Home> next = new LinkedHashMap<>(current); next.remove(home.nameKey()); profiles.put(owner, Map.copyOf(next)); }
                Bukkit.getPluginManager().callEvent(new PlexonHomeDeletedEvent(player, home.view()));
                return true;
            })).whenComplete((ok, error) -> mutating.remove(owner));
        }).exceptionally(error -> { mutating.remove(owner); plugin.getLogger().severe("Failed to delete home from SQLite: " + rootMessage(error)); return false; });
    }

    public CompletableFuture<Boolean> renameHome(Player player, String oldName, String newName) {
        if (player == null || !player.hasPermission("plexonhomes.rename")) return CompletableFuture.completedFuture(false);
        UUID owner = player.getUniqueId();
        return ensureLoaded(owner).thenCompose(ignored -> onMain(() -> prepareRename(player, oldName, newName))).thenCompose(plan -> {
            if (plan == null) return CompletableFuture.completedFuture(false);
            return repository.upsert(plan.renamed).thenCompose(ignored -> onMain(() -> {
                Map<String, Home> current = profiles.get(owner);
                if (current != null) { Map<String, Home> next = new LinkedHashMap<>(current); next.remove(plan.previous.nameKey()); next.put(plan.renamed.nameKey(), plan.renamed); profiles.put(owner, Map.copyOf(next)); }
                Bukkit.getPluginManager().callEvent(new PlexonHomeRenamedEvent(player, plan.previous.view(), plan.renamed.view()));
                return true;
            })).whenComplete((ok, error) -> mutating.remove(owner));
        }).exceptionally(error -> { mutating.remove(owner); plugin.getLogger().severe("Failed to rename home in SQLite: " + rootMessage(error)); return false; });
    }

    private RenamePlan prepareRename(Player player, String oldName, String newName) {
        requirePrimary(); UUID owner = player.getUniqueId(); if (!mutating.add(owner)) return null;
        Map<String, Home> current = profiles.get(owner); if (current == null) { mutating.remove(owner); return null; }
        Optional<String> oldKey = HomeNames.normalize(oldName, config.get().maxNameLength()); Optional<String> newKey = HomeNames.normalize(newName, config.get().maxNameLength());
        if (oldKey.isEmpty() || newKey.isEmpty() || oldKey.get().equals(newKey.get()) || current.containsKey(newKey.get())) { mutating.remove(owner); return null; }
        Home previous = current.get(oldKey.get()); if (previous == null) { mutating.remove(owner); return null; }
        return new RenamePlan(previous, previous.withName(newKey.get(), newName.trim(), System.currentTimeMillis()));
    }

    public CompletableFuture<Boolean> updateHomeLocation(Player player, UUID homeId, long expectedRevision) {
        if (player == null || !player.hasPermission("plexonhomes.sethome")) return CompletableFuture.completedFuture(false);
        UUID owner = player.getUniqueId();
        return ensureLoaded(owner).thenCompose(ignored -> onMain(() -> {
            if (!mutating.add(owner)) return null;
            Home previous = findByIdCached(owner, homeId).orElse(null); Location location = player.getLocation();
            if (previous == null || previous.revision() != expectedRevision || !config.get().canSetIn(player.getWorld()) || !validLocation(location) || (config.get().safeTeleport() && !safeTeleport.isSafe(location))) {
                mutating.remove(owner); return null;
            }
            return new SetPlan(previous, previous.withLocation(location, System.currentTimeMillis()));
        })).thenCompose(plan -> {
            if (plan == null) return CompletableFuture.completedFuture(false);
            return repository.upsert(plan.home).thenCompose(ignored -> onMain(() -> {
                Map<String, Home> current = profiles.get(owner);
                if (current != null) { Map<String, Home> next = new LinkedHashMap<>(current); next.put(plan.home.nameKey(), plan.home); profiles.put(owner, Map.copyOf(next)); }
                Bukkit.getPluginManager().callEvent(new PlexonHomeSetEvent(player, plan.home.view(), true)); return true;
            })).whenComplete((ok, error) -> mutating.remove(owner));
        }).exceptionally(error -> { mutating.remove(owner); plugin.getLogger().severe("Failed to update home location: " + rootMessage(error)); return false; });
    }

    public CompletableFuture<Boolean> importHome(Home home, boolean replaceExisting) {
        UUID owner = home.ownerId();
        return ensureLoaded(owner).thenCompose(ignored -> onMain(() -> {
            if (!mutating.add(owner)) return null;
            Map<String, Home> current = profiles.getOrDefault(owner, Map.of());
            if (!replaceExisting && current.containsKey(home.nameKey())) { mutating.remove(owner); return null; }
            Home collision = current.get(home.nameKey());
            Home candidate = collision != null && replaceExisting
                    ? new Home(owner, collision.homeId(), home.nameKey(), home.displayName(), home.worldId(), home.worldName(), home.x(), home.y(), home.z(), home.yaw(), home.pitch(), collision.createdAt(), System.currentTimeMillis(), collision.revision() + 1L)
                    : home;
            return candidate;
        })).thenCompose(candidate -> {
            if (candidate == null) return CompletableFuture.completedFuture(false);
            return repository.upsert(candidate).thenCompose(ignored -> onMain(() -> {
                Map<String, Home> current = profiles.get(owner); if (current != null) { Map<String, Home> next = new LinkedHashMap<>(current); next.put(candidate.nameKey(), candidate); profiles.put(owner, Map.copyOf(next)); }
                return true;
            })).whenComplete((ok, error) -> mutating.remove(owner));
        }).exceptionally(error -> { mutating.remove(owner); plugin.getLogger().severe("Failed to import home: " + rootMessage(error)); return false; });
    }

    public CompletableFuture<List<Home>> inspectPersisted(UUID owner) { return repository.loadHomes(owner); }

    public void clearProfile(UUID playerId) {
        loadGenerations.computeIfAbsent(playerId, ignored -> new AtomicLong()).incrementAndGet(); profiles.remove(playerId); loading.remove(playerId);
    }
    public int loadedProfiles() { return profiles.size(); }
    public int cachedHomes() { return profiles.values().stream().mapToInt(Map::size).sum(); }
    public int activeMutations() { return mutating.size(); }

    private static boolean validLocation(Location location) {
        return location != null && location.getWorld() != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }

    private <T> CompletableFuture<T> onMain(java.util.concurrent.Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) { try { return CompletableFuture.completedFuture(callable.call()); } catch (Exception e) { return CompletableFuture.failedFuture(e); } }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> { try { future.complete(callable.call()); } catch (Throwable t) { future.completeExceptionally(t); } }); return future;
    }

    private static String rootMessage(Throwable error) { Throwable cursor = error; while (cursor.getCause() != null) cursor = cursor.getCause(); return String.valueOf(cursor.getMessage()); }
    private static void requirePrimary() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Home mutation must run on the primary thread"); }
    private record SetPlan(Home previous, Home home) {}
    private record RenamePlan(Home previous, Home renamed) {}
}
