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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TeleportService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final HomeService homes;
    private final SafeTeleportService safeTeleport;
    private final EconomyBridge economy;
    private final PlayerWatchService watches;
    private final Supplier<Snapshot> config;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();

    public TeleportService(JavaPlugin plugin, HomeService homes, SafeTeleportService safeTeleport, EconomyBridge economy, PlayerWatchService watches, Supplier<Snapshot> config) {
        this.plugin = plugin;
        this.homes = homes;
        this.safeTeleport = safeTeleport;
        this.economy = economy;
        this.watches = watches;
        this.config = config;
    }

    public CompletableFuture<HomeTeleportStatus> request(Player player, String name) {
        String requested = name == null || name.isBlank() ? config.get().defaultName() : name;
        return homes.ensureLoaded(player.getUniqueId()).thenCompose(ignored -> onMain(() -> startLoaded(player, requested))).thenCompose(future -> future);
    }

    private CompletableFuture<HomeTeleportStatus> startLoaded(Player player, String requested) {
        if (!player.isOnline()) return CompletableFuture.completedFuture(HomeTeleportStatus.FAILED);
        Optional<Home> found = homes.findCached(player.getUniqueId(), requested);
        if (found.isEmpty()) return CompletableFuture.completedFuture(HomeTeleportStatus.HOME_NOT_FOUND);
        Home home = found.get();
        World world = Bukkit.getWorld(home.worldId());
        if (world == null && home.worldName() != null) world = Bukkit.getWorld(home.worldName());
        if (world == null || !config.get().canTeleportTo(world)) return CompletableFuture.completedFuture(HomeTeleportStatus.WORLD_BLOCKED);
        if (coolingDown(player)) return CompletableFuture.completedFuture(HomeTeleportStatus.COOLDOWN);

        return safeTeleport.resolve(home).thenCompose(destination -> {
            if (destination.isEmpty()) {
                fireCancelled(player, home, HomeTeleportCancellationReason.UNSAFE_DESTINATION);
                return CompletableFuture.completedFuture(HomeTeleportStatus.UNSAFE_DESTINATION);
            }
            return beginWarmup(player, home, destination.get());
        });
    }

    private CompletableFuture<HomeTeleportStatus> beginWarmup(Player player, Home home, Location destination) {
        PlexonHomeTeleportStartEvent start = new PlexonHomeTeleportStartEvent(player, home.view(), destination);
        Bukkit.getPluginManager().callEvent(start);
        if (start.isCancelled()) return CompletableFuture.completedFuture(HomeTeleportStatus.CANCELLED);

        Pending old = pending.get(player.getUniqueId());
        if (old != null) cancel(old, HomeTeleportCancellationReason.OTHER, false);

        Snapshot snapshot = config.get();
        int warmup = player.hasPermission("plexonhomes.teleport.warmup.bypass") ? 0 : snapshot.warmupSeconds();
        if (warmup <= 0) return finishImmediate(player, home);

        long generation = generations.incrementAndGet();
        Location origin = player.getLocation().clone();
        WatchHandle handle = watches.watch(player.getUniqueId(), EnumSet.of(WatchType.MOVEMENT, WatchType.DAMAGE, WatchType.QUIT, WatchType.WORLD_CHANGE, WatchType.DEATH), activity -> onActivity(generation, activity));
        Pending state = new Pending(generation, player.getUniqueId(), home, origin, destination.clone(), handle);
        Pending previous = pending.put(player.getUniqueId(), state);
        if (previous != null) cancel(previous, HomeTeleportCancellationReason.OTHER, false);
        state.task = Bukkit.getScheduler().runTaskLater(plugin, () -> completeWarmup(state), warmup * 20L);
        player.sendActionBar(Component.text("Teleporting to " + home.displayName() + " in " + warmup + "s..."));
        return CompletableFuture.completedFuture(HomeTeleportStatus.STARTED);
    }

    private CompletableFuture<HomeTeleportStatus> finishImmediate(Player player, Home home) {
        CompletableFuture<HomeTeleportStatus> result = new CompletableFuture<>();
        finalTeleport(player, home, result);
        return result;
    }

    private void completeWarmup(Pending state) {
        if (pending.get(state.playerId) != state) return;
        Player player = Bukkit.getPlayer(state.playerId);
        if (player == null || !player.isOnline()) { cancel(state, HomeTeleportCancellationReason.QUIT, false); return; }
        Home current = homes.findCached(state.playerId, state.home.id()).orElse(null);
        if (current == null) { cancel(state, HomeTeleportCancellationReason.HOME_DELETED, true); return; }
        if (current.revision() != state.home.revision()) { cancel(state, HomeTeleportCancellationReason.HOME_CHANGED, true); return; }
        finalTeleport(player, current, null);
    }

    private void finalTeleport(Player player, Home home, CompletableFuture<HomeTeleportStatus> immediateResult) {
        safeTeleport.resolve(home).whenComplete((destination, error) -> {
            if (error != null || destination.isEmpty()) {
                Pending state = pending.get(player.getUniqueId());
                if (state != null) cancel(state, HomeTeleportCancellationReason.UNSAFE_DESTINATION, true);
                if (immediateResult != null) immediateResult.complete(HomeTeleportStatus.UNSAFE_DESTINATION);
                return;
            }
            Location target = destination.get();
            ChargeResult charge = economy.charge(player, config.get().fee());
            if (!charge.success()) {
                Pending state = pending.get(player.getUniqueId());
                if (state != null) cancel(state, HomeTeleportCancellationReason.ECONOMY, true);
                if (immediateResult != null) immediateResult.complete(charge.reason().equals("economy-unavailable") ? HomeTeleportStatus.ECONOMY_UNAVAILABLE : HomeTeleportStatus.INSUFFICIENT_FUNDS);
                return;
            }

            Pending state = pending.remove(player.getUniqueId());
            if (state != null) state.close();
            Location from = player.getLocation().clone();
            player.teleportAsync(target).whenComplete((success, teleportError) -> onMain(() -> {
                if (Boolean.TRUE.equals(success) && teleportError == null) {
                    if (!player.hasPermission("plexonhomes.teleport.cooldown.bypass") && config.get().cooldownSeconds() > 0) cooldowns.put(player.getUniqueId(), System.nanoTime());
                    Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportEvent(player, home.view(), from, target, charge.charged()));
                    player.sendMessage(Component.text("Teleported to " + home.displayName() + "."));
                    if (immediateResult != null) immediateResult.complete(HomeTeleportStatus.TELEPORTED);
                } else {
                    economy.refund(player, charge.charged());
                    fireCancelled(player, home, HomeTeleportCancellationReason.OTHER);
                    if (immediateResult != null) immediateResult.complete(HomeTeleportStatus.FAILED);
                }
                return null;
            }));
        });
    }

    private void onActivity(long generation, PlayerActivity activity) {
        Pending state = pending.get(activity.playerId());
        if (state == null || state.generation != generation) return;
        Snapshot snapshot = config.get();
        if (activity.signal() == Signal.DAMAGED && snapshot.cancelOnDamage()) { cancel(state, HomeTeleportCancellationReason.DAMAGED, true); return; }
        if (activity.signal() == Signal.QUIT) { cancel(state, HomeTeleportCancellationReason.QUIT, false); return; }
        if (activity.signal() == Signal.DIED) { cancel(state, HomeTeleportCancellationReason.DIED, false); return; }
        if (activity.signal() == Signal.WORLD_CHANGED) { cancel(state, HomeTeleportCancellationReason.WORLD_CHANGED, true); return; }
        if (activity.signal() == Signal.MOVED && snapshot.cancelOnMovement() && activity.to() != null) {
            if (!state.origin.getWorld().getUID().equals(activity.to().worldId())) { cancel(state, HomeTeleportCancellationReason.WORLD_CHANGED, true); return; }
            double dx = state.origin.getX() - activity.to().x();
            double dy = state.origin.getY() - activity.to().y();
            double dz = state.origin.getZ() - activity.to().z();
            double threshold = snapshot.movementThreshold();
            if (dx * dx + dy * dy + dz * dz > threshold * threshold) cancel(state, HomeTeleportCancellationReason.MOVED, true);
        }
    }

    public boolean isPending(UUID playerId) { return pending.containsKey(playerId); }
    public int pendingCount() { return pending.size(); }
    public int cooldownCount() { return cooldowns.size(); }

    private boolean coolingDown(Player player) {
        if (player.hasPermission("plexonhomes.teleport.cooldown.bypass")) return false;
        long seconds = config.get().cooldownSeconds();
        if (seconds <= 0) return false;
        Long start = cooldowns.get(player.getUniqueId());
        if (start == null) return false;
        if (System.nanoTime() - start >= seconds * 1_000_000_000L) { cooldowns.remove(player.getUniqueId(), start); return false; }
        return true;
    }

    private void cancel(Pending state, HomeTeleportCancellationReason reason, boolean message) {
        if (!pending.remove(state.playerId, state)) return;
        state.close();
        Player player = Bukkit.getPlayer(state.playerId);
        if (player != null) {
            fireCancelled(player, state.home, reason);
            if (message) player.sendMessage(Component.text(cancelMessage(reason)));
        }
    }

    private static String cancelMessage(HomeTeleportCancellationReason reason) {
        return switch (reason) {
            case MOVED -> "Teleport cancelled because you moved.";
            case DAMAGED -> "Teleport cancelled because you took damage.";
            case WORLD_CHANGED -> "Teleport cancelled because your world changed.";
            case UNSAFE_DESTINATION -> "Teleport cancelled because the destination is unsafe.";
            case HOME_CHANGED, HOME_DELETED -> "Teleport cancelled because the home changed.";
            case ECONOMY -> "Teleport cancelled because the fee could not be charged.";
            default -> "Teleport cancelled.";
        };
    }

    private static void fireCancelled(Player player, Home home, HomeTeleportCancellationReason reason) {
        Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportCancelledEvent(player, home.view(), reason));
    }

    private <T> CompletableFuture<T> onMain(java.util.concurrent.Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) {
            try { return CompletableFuture.completedFuture(callable.call()); }
            catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(callable.call()); }
            catch (Throwable throwable) { future.completeExceptionally(throwable); }
        });
        return future;
    }

    @Override public void close() {
        for (Pending state : pending.values()) cancel(state, HomeTeleportCancellationReason.PLUGIN_DISABLED, false);
        pending.clear(); cooldowns.clear();
    }

    private static final class Pending {
        final long generation;
        final UUID playerId;
        final Home home;
        final Location origin;
        final Location destination;
        final WatchHandle watch;
        BukkitTask task;
        Pending(long generation, UUID playerId, Home home, Location origin, Location destination, WatchHandle watch) {
            this.generation = generation; this.playerId = playerId; this.home = home; this.origin = origin; this.destination = destination; this.watch = watch;
        }
        void close() {
            watch.close();
            if (task != null) task.cancel();
        }
    }
}
