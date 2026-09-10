package com.plexon.homes.service;

import com.plexon.homes.api.HomeTeleportStatus;
import com.plexon.homes.api.event.HomeTeleportCancellationReason;
import com.plexon.homes.api.event.PlexonHomeTeleportCancelledEvent;
import com.plexon.homes.api.event.PlexonHomeTeleportEvent;
import com.plexon.homes.api.event.PlexonHomeTeleportStartEvent;
import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.integration.EconomyBridge;
import com.plexon.homes.integration.EconomyBridge.ChargeResult;
import com.plexon.homes.model.Home;
import com.zpkdxgames.plexoncore.player.PlayerWatchService;
import com.zpkdxgames.plexoncore.player.PlayerWatchService.PlayerActivity;
import com.zpkdxgames.plexoncore.player.PlayerWatchService.Signal;
import com.zpkdxgames.plexoncore.player.PlayerWatchService.WatchHandle;
import com.zpkdxgames.plexoncore.player.PlayerWatchService.WatchType;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TeleportService implements AutoCloseable {
    private enum Phase { RESOLVING, WARMUP, FINAL_RESOLVE, TELEPORTING }
    private final JavaPlugin plugin; private final HomeService homes; private final SafeTeleportService safeTeleport; private final EconomyBridge economy;
    private final PlayerWatchService watches; private final Supplier<Snapshot> config;
    private final ConcurrentHashMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final BukkitTask warmupCoordinator;

    public TeleportService(JavaPlugin plugin, HomeService homes, SafeTeleportService safeTeleport, EconomyBridge economy, PlayerWatchService watches, Supplier<Snapshot> config) {
        this.plugin = plugin; this.homes = homes; this.safeTeleport = safeTeleport; this.economy = economy; this.watches = watches; this.config = config;
        this.warmupCoordinator = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWarmups, 1L, 1L);
    }

    public CompletableFuture<HomeTeleportStatus> request(Player player, String name) {
        String requested = name == null || name.isBlank() ? config.get().defaultName() : name;
        return homes.ensureLoaded(player.getUniqueId()).thenCompose(ignored -> onMain(() -> startLoaded(player, requested))).thenCompose(future -> future);
    }

    private CompletableFuture<HomeTeleportStatus> startLoaded(Player player, String requested) {
        if (!player.isOnline()) return CompletableFuture.completedFuture(HomeTeleportStatus.FAILED);
        Optional<Home> found = homes.findCached(player.getUniqueId(), requested); if (found.isEmpty()) return CompletableFuture.completedFuture(HomeTeleportStatus.HOME_NOT_FOUND);
        Home home = found.get(); if (!safeTeleport.structurallyValid(home)) return CompletableFuture.completedFuture(HomeTeleportStatus.UNSAFE_DESTINATION);
        World world = Bukkit.getWorld(home.worldId()); if (world == null && home.worldName() != null) world = Bukkit.getWorld(home.worldName());
        if (world == null || !config.get().canTeleportTo(world)) return CompletableFuture.completedFuture(HomeTeleportStatus.WORLD_BLOCKED);
        if (coolingDown(player)) return CompletableFuture.completedFuture(HomeTeleportStatus.COOLDOWN);
        Attempt attempt = new Attempt(tokens.incrementAndGet(), player.getUniqueId(), home, player.getLocation().clone());
        if (attempts.putIfAbsent(player.getUniqueId(), attempt) != null) return CompletableFuture.completedFuture(HomeTeleportStatus.ALREADY_PENDING);
        resolveInitial(player, attempt); return attempt.requestResult;
    }

    private void resolveInitial(Player player, Attempt attempt) {
        safeTeleport.resolve(attempt.home).whenComplete((destination, error) -> onMain(() -> {
            if (!owns(attempt)) return null;
            if (error != null || destination.isEmpty()) { terminate(attempt, player, HomeTeleportStatus.UNSAFE_DESTINATION, HomeTeleportCancellationReason.UNSAFE_DESTINATION, false); return null; }
            Home current = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
            if (current == null) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_DELETED, false); return null; }
            if (current.revision() != attempt.home.revision()) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_CHANGED, false); return null; }
            attempt.destination = destination.get().clone();
            PlexonHomeTeleportStartEvent start = new PlexonHomeTeleportStartEvent(player, current.view(), attempt.destination);
            Bukkit.getPluginManager().callEvent(start);
            if (start.isCancelled()) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.OTHER, false); return null; }
            attempt.watch = watches.watch(player.getUniqueId(), EnumSet.of(WatchType.MOVEMENT, WatchType.DAMAGE, WatchType.QUIT, WatchType.WORLD_CHANGE, WatchType.DEATH), activity -> onActivity(attempt.token, activity));
            int warmup = player.hasPermission("plexonhomes.teleport.warmup.bypass") ? 0 : config.get().warmupSeconds();
            if (warmup <= 0) { beginFinalResolve(attempt, player); return null; }
            attempt.phase = Phase.WARMUP; attempt.deadlineNanos = System.nanoTime() + warmup * 1_000_000_000L;
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gold>Teleporting to <white>" + MiniMessage.miniMessage().escapeTags(current.displayName()) + "</white> in " + warmup + "s...</gold>"));
            attempt.requestResult.complete(HomeTeleportStatus.STARTED); return null;
        }));
    }

    private void tickWarmups() {
        long now = System.nanoTime();
        for (Attempt attempt : attempts.values()) if (attempt.phase == Phase.WARMUP && now >= attempt.deadlineNanos) {
            Player player = Bukkit.getPlayer(attempt.playerId);
            if (player == null || !player.isOnline()) terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.QUIT, false);
            else beginFinalResolve(attempt, player);
        }
    }

    private void beginFinalResolve(Attempt attempt, Player player) {
        if (!owns(attempt) || attempt.phase == Phase.FINAL_RESOLVE || attempt.phase == Phase.TELEPORTING) return;
        Home current = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
        if (current == null) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_DELETED, true); return; }
        if (current.revision() != attempt.home.revision()) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_CHANGED, true); return; }
        World world = Bukkit.getWorld(current.worldId()); if (world == null || !config.get().canTeleportTo(world)) { terminate(attempt, player, HomeTeleportStatus.WORLD_BLOCKED, HomeTeleportCancellationReason.WORLD_CHANGED, true); return; }
        attempt.phase = Phase.FINAL_RESOLVE;
        safeTeleport.resolve(current).whenComplete((destination, error) -> onMain(() -> {
            if (!owns(attempt)) return null;
            if (error != null || destination.isEmpty()) { terminate(attempt, player, HomeTeleportStatus.UNSAFE_DESTINATION, HomeTeleportCancellationReason.UNSAFE_DESTINATION, true); return null; }
            Home latest = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
            if (latest == null || latest.revision() != attempt.home.revision()) { terminate(attempt, player, HomeTeleportStatus.CANCELLED, latest == null ? HomeTeleportCancellationReason.HOME_DELETED : HomeTeleportCancellationReason.HOME_CHANGED, true); return null; }
            ChargeResult charge = economy.charge(player, config.get().fee());
            if (!charge.success()) {
                HomeTeleportStatus status = charge.reason().equals("economy-unavailable") ? HomeTeleportStatus.ECONOMY_UNAVAILABLE : HomeTeleportStatus.INSUFFICIENT_FUNDS;
                terminate(attempt, player, status, HomeTeleportCancellationReason.ECONOMY, true); return null;
            }
            attempt.charged = charge.charged(); attempt.phase = Phase.TELEPORTING; Location target = destination.get().clone(); Location from = player.getLocation().clone();
            player.teleportAsync(target).whenComplete((success, teleportError) -> onMain(() -> {
                if (!owns(attempt)) return null;
                if (Boolean.TRUE.equals(success) && teleportError == null) {
                    attempts.remove(attempt.playerId, attempt); attempt.closeWatch();
                    if (!player.hasPermission("plexonhomes.teleport.cooldown.bypass") && config.get().cooldownSeconds() > 0) cooldowns.put(player.getUniqueId(), System.nanoTime());
                    Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportEvent(player, latest.view(), from, target, attempt.charged));
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Teleported to <white>" + MiniMessage.miniMessage().escapeTags(latest.displayName()) + "</white>.</green>"));
                    attempt.requestResult.complete(HomeTeleportStatus.TELEPORTED);
                } else terminate(attempt, player, HomeTeleportStatus.FAILED, HomeTeleportCancellationReason.OTHER, false);
                return null;
            }));
            return null;
        }));
    }

    private void onActivity(long token, PlayerActivity activity) {
        Attempt attempt = attempts.get(activity.playerId()); if (attempt == null || attempt.token != token) return;
        Snapshot snapshot = config.get(); Signal signal = activity.signal();
        if (signal == Signal.QUIT) { terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.QUIT, false); return; }
        if (signal == Signal.DIED) { terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.DIED, false); return; }
        if (signal == Signal.WORLD_CHANGED) { terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.WORLD_CHANGED, true); return; }
        if (attempt.phase != Phase.WARMUP) return;
        if (signal == Signal.DAMAGED && snapshot.cancelOnDamage()) { terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.DAMAGED, true); return; }
        if (signal == Signal.MOVED && snapshot.cancelOnMovement() && activity.to() != null) {
            if (!attempt.origin.getWorld().getUID().equals(activity.to().worldId())) { terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.WORLD_CHANGED, true); return; }
            double dx = attempt.origin.getX() - activity.to().x(), dy = attempt.origin.getY() - activity.to().y(), dz = attempt.origin.getZ() - activity.to().z();
            double threshold = snapshot.movementThreshold(); if (dx * dx + dy * dy + dz * dz > threshold * threshold) terminate(attempt, Bukkit.getPlayer(activity.playerId()), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.MOVED, true);
        }
    }

    private void terminate(Attempt attempt, Player player, HomeTeleportStatus status, HomeTeleportCancellationReason reason, boolean message) {
        if (!attempts.remove(attempt.playerId, attempt)) return;
        attempt.closeWatch();
        if (attempt.charged > 0.0D && !attempt.refunded) { attempt.refunded = true; if (player != null && !economy.refund(player, attempt.charged)) plugin.getLogger().severe("Vault did not confirm refund for home teleport attempt " + attempt.token); }
        if (player != null) {
            Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportCancelledEvent(player, attempt.home.view(), reason));
            if (message) player.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + MiniMessage.miniMessage().escapeTags(cancelMessage(reason)) + "</red>"));
        }
        attempt.requestResult.complete(status);
    }

    private boolean coolingDown(Player player) {
        if (player.hasPermission("plexonhomes.teleport.cooldown.bypass")) return false;
        long seconds = config.get().cooldownSeconds(); if (seconds <= 0) return false;
        Long start = cooldowns.get(player.getUniqueId()); if (start == null) return false;
        if (System.nanoTime() - start >= seconds * 1_000_000_000L) { cooldowns.remove(player.getUniqueId(), start); return false; } return true;
    }

    private boolean owns(Attempt attempt) { return attempts.get(attempt.playerId) == attempt; }
    public boolean isPending(UUID playerId) { return attempts.containsKey(playerId); }
    public int pendingCount() { return attempts.size(); }
    public long inFlightCount() { return attempts.values().stream().filter(a -> a.phase == Phase.TELEPORTING).count(); }
    public int cooldownCount() { return cooldowns.size(); }

    private static String cancelMessage(HomeTeleportCancellationReason reason) {
        return switch (reason) {
            case MOVED -> "Teleport cancelled because you moved."; case DAMAGED -> "Teleport cancelled because you took damage.";
            case WORLD_CHANGED -> "Teleport cancelled because your world changed."; case UNSAFE_DESTINATION -> "Teleport cancelled because the destination is unsafe.";
            case HOME_CHANGED, HOME_DELETED -> "Teleport cancelled because the home changed."; case ECONOMY -> "Teleport cancelled because the fee could not be charged.";
            default -> "Teleport cancelled.";
        };
    }

    private <T> CompletableFuture<T> onMain(java.util.concurrent.Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) { try { return CompletableFuture.completedFuture(callable.call()); } catch (Exception e) { return CompletableFuture.failedFuture(e); } }
        CompletableFuture<T> future = new CompletableFuture<>(); Bukkit.getScheduler().runTask(plugin, () -> { try { future.complete(callable.call()); } catch (Throwable t) { future.completeExceptionally(t); } }); return future;
    }

    @Override public void close() {
        warmupCoordinator.cancel();
        for (Attempt attempt : attempts.values()) terminate(attempt, Bukkit.getPlayer(attempt.playerId), HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.PLUGIN_DISABLED, false);
        attempts.clear(); cooldowns.clear();
    }

    private static final class Attempt {
        final long token; final UUID playerId; final Home home; final Location origin; final CompletableFuture<HomeTeleportStatus> requestResult = new CompletableFuture<>();
        volatile Phase phase = Phase.RESOLVING; volatile long deadlineNanos; volatile Location destination; volatile WatchHandle watch; volatile double charged; volatile boolean refunded;
        Attempt(long token, UUID playerId, Home home, Location origin) { this.token = token; this.playerId = playerId; this.home = home; this.origin = origin; }
        void closeWatch() { WatchHandle current = watch; watch = null; if (current != null) current.close(); }
    }
}
