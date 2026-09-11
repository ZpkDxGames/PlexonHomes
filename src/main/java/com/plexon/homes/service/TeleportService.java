package com.plexon.homes.service;

import com.plexon.homes.api.HomeLimitView;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TeleportService implements AutoCloseable {
    enum Phase { RESOLVING, WARMUP, FINAL_RESOLVE, TELEPORTING }

    private final JavaPlugin plugin;
    private final HomeService homes;
    private final SafeTeleportService safeTeleport;
    private final EconomyBridge economy;
    private final PlayerWatchService watches;
    private final Supplier<Snapshot> config;
    private final ConcurrentHashMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final BukkitTask warmupCoordinator;

    public TeleportService(JavaPlugin plugin, HomeService homes, SafeTeleportService safeTeleport, EconomyBridge economy,
                           PlayerWatchService watches, Supplier<Snapshot> config) {
        this.plugin = plugin;
        this.homes = homes;
        this.safeTeleport = safeTeleport;
        this.economy = economy;
        this.watches = watches;
        this.config = config;
        this.warmupCoordinator = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWarmups, 1L, 1L);
    }

    public CompletableFuture<HomeTeleportStatus> request(Player player, String name) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(HomeTeleportStatus.FAILED);
        if (!player.hasPermission("plexonhomes.use")) return CompletableFuture.completedFuture(HomeTeleportStatus.PERMISSION_DENIED);
        String requested = name == null || name.isBlank() ? config.get().defaultName() : name;
        return homes.ensureLoaded(player.getUniqueId())
                .thenCompose(ignored -> onMain(() -> startLoaded(player, requested)))
                .thenCompose(future -> future);
    }

    private CompletableFuture<HomeTeleportStatus> startLoaded(Player player, String requested) {
        if (!player.isOnline()) return CompletableFuture.completedFuture(HomeTeleportStatus.FAILED);
        if (!player.hasPermission("plexonhomes.use")) return CompletableFuture.completedFuture(HomeTeleportStatus.PERMISSION_DENIED);
        Optional<Home> found = homes.findCached(player.getUniqueId(), requested);
        if (found.isEmpty()) return CompletableFuture.completedFuture(HomeTeleportStatus.HOME_NOT_FOUND);
        Home home = found.get();
        if (!canUse(player, home)) return CompletableFuture.completedFuture(HomeTeleportStatus.LIMIT_RESTRICTED);
        if (!safeTeleport.structurallyValid(home)) return CompletableFuture.completedFuture(HomeTeleportStatus.UNSAFE_DESTINATION);
        World world = resolveWorld(home);
        if (world == null || !config.get().canTeleportTo(world)) return CompletableFuture.completedFuture(HomeTeleportStatus.WORLD_BLOCKED);
        if (coolingDown(player)) return CompletableFuture.completedFuture(HomeTeleportStatus.COOLDOWN);

        Snapshot snapshot = config.get();
        Attempt attempt = new Attempt(tokens.incrementAndGet(), player.getUniqueId(), player, home, player.getLocation().clone(), snapshot.fee());
        if (attempts.putIfAbsent(player.getUniqueId(), attempt) != null) return CompletableFuture.completedFuture(HomeTeleportStatus.ALREADY_PENDING);
        try {
            attempt.watch = watches.watch(player.getUniqueId(),
                    EnumSet.of(WatchType.MOVEMENT, WatchType.DAMAGE, WatchType.QUIT, WatchType.WORLD_CHANGE, WatchType.DEATH),
                    activity -> onActivity(attempt.token, activity));
        } catch (Throwable watchFailure) {
            attempts.remove(player.getUniqueId(), attempt);
            plugin.getLogger().severe("Unable to register PlexonCore player-watch for home teleport: " + rootMessage(watchFailure));
            attempt.requestResult.complete(HomeTeleportStatus.FAILED);
            return attempt.requestResult;
        }
        resolveInitial(player, attempt);
        return attempt.requestResult;
    }

    private void resolveInitial(Player player, Attempt attempt) {
        safeTeleport.resolve(attempt.home).whenComplete((destination, error) -> onMain(() -> {
            if (!owns(attempt)) return null;
            if (error != null || destination.isEmpty()) {
                terminate(attempt, player, HomeTeleportStatus.UNSAFE_DESTINATION, HomeTeleportCancellationReason.UNSAFE_DESTINATION, false);
                return null;
            }
            Home current = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
            if (current == null) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_DELETED, false);
                return null;
            }
            if (current.revision() != attempt.home.revision()) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_CHANGED, false);
                return null;
            }
            if (!canUse(player, current)) {
                terminate(attempt, player, HomeTeleportStatus.LIMIT_RESTRICTED, HomeTeleportCancellationReason.OTHER, false);
                return null;
            }
            attempt.destination = destination.get().clone();
            PlexonHomeTeleportStartEvent start = new PlexonHomeTeleportStartEvent(player, current.view(), attempt.destination);
            Bukkit.getPluginManager().callEvent(start);
            if (start.isCancelled()) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.OTHER, false);
                return null;
            }
            int warmup = player.hasPermission("plexonhomes.teleport.warmup.bypass") ? 0 : config.get().warmupSeconds();
            if (warmup <= 0) {
                beginFinalResolve(attempt, player);
                return null;
            }
            attempt.phase = Phase.WARMUP;
            attempt.deadlineNanos = saturatingDeadline(System.nanoTime(), warmup);
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gold>Teleporting to <white>"
                    + MiniMessage.miniMessage().escapeTags(current.displayName()) + "</white> in " + warmup + "s...</gold>"));
            attempt.requestResult.complete(HomeTeleportStatus.STARTED);
            return null;
        }).exceptionally(callbackError -> {
            plugin.getLogger().severe("Initial home teleport callback failed: " + rootMessage(callbackError));
            return null;
        }));
    }

    private void tickWarmups() {
        long now = System.nanoTime();
        for (Attempt attempt : attempts.values()) {
            if (attempt.phase != Phase.WARMUP || now - attempt.deadlineNanos < 0L) continue;
            Player player = Bukkit.getPlayer(attempt.playerId);
            if (player == null || !player.isOnline()) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.QUIT, false);
            } else {
                beginFinalResolve(attempt, player);
            }
        }
    }

    private void beginFinalResolve(Attempt attempt, Player player) {
        if (!owns(attempt) || attempt.phase == Phase.FINAL_RESOLVE || attempt.phase == Phase.TELEPORTING) return;
        Home current = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
        if (current == null) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_DELETED, true);
            return;
        }
        if (current.revision() != attempt.home.revision()) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.HOME_CHANGED, true);
            return;
        }
        if (!canUse(player, current)) {
            terminate(attempt, player, HomeTeleportStatus.LIMIT_RESTRICTED, HomeTeleportCancellationReason.OTHER, true);
            return;
        }
        World world = resolveWorld(current);
        if (world == null || !config.get().canTeleportTo(world)) {
            terminate(attempt, player, HomeTeleportStatus.WORLD_BLOCKED, HomeTeleportCancellationReason.WORLD_CHANGED, true);
            return;
        }

        attempt.phase = Phase.FINAL_RESOLVE;
        safeTeleport.resolve(current).whenComplete((destination, error) -> onMain(() -> {
            if (!owns(attempt)) return null;
            if (error != null || destination.isEmpty()) {
                terminate(attempt, player, HomeTeleportStatus.UNSAFE_DESTINATION, HomeTeleportCancellationReason.UNSAFE_DESTINATION, true);
                return null;
            }
            Home latest = homes.findByIdCached(attempt.playerId, attempt.home.homeId()).orElse(null);
            if (latest == null || latest.revision() != attempt.home.revision()) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED,
                        latest == null ? HomeTeleportCancellationReason.HOME_DELETED : HomeTeleportCancellationReason.HOME_CHANGED, true);
                return null;
            }
            if (!canUse(player, latest)) {
                terminate(attempt, player, HomeTeleportStatus.LIMIT_RESTRICTED, HomeTeleportCancellationReason.OTHER, true);
                return null;
            }

            ChargeResult charge = economy.charge(player, attempt.fee);
            if (!charge.success()) {
                HomeTeleportStatus status = charge.reason().equals("economy-unavailable")
                        ? HomeTeleportStatus.ECONOMY_UNAVAILABLE : HomeTeleportStatus.INSUFFICIENT_FUNDS;
                terminate(attempt, player, status, HomeTeleportCancellationReason.ECONOMY, true);
                return null;
            }
            attempt.charged = charge.charged();
            attempt.phase = Phase.TELEPORTING;
            Location target = destination.get().clone();
            Location from = player.getLocation().clone();
            player.teleportAsync(target).whenComplete((success, teleportError) -> onMain(() -> {
                if (!owns(attempt)) return null;
                if (Boolean.TRUE.equals(success) && teleportError == null) {
                    attempts.remove(attempt.playerId, attempt);
                    attempt.closeWatch();
                    if (!player.hasPermission("plexonhomes.teleport.cooldown.bypass") && config.get().cooldownSeconds() > 0) {
                        cooldowns.put(player.getUniqueId(), System.nanoTime());
                    }
                    Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportEvent(player, latest.view(), from, target, attempt.charged));
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Teleported to <white>"
                            + MiniMessage.miniMessage().escapeTags(latest.displayName()) + "</white>.</green>"));
                    attempt.requestResult.complete(HomeTeleportStatus.TELEPORTED);
                } else {
                    terminate(attempt, player, HomeTeleportStatus.FAILED, HomeTeleportCancellationReason.OTHER, false);
                }
                return null;
            }).exceptionally(callbackError -> {
                plugin.getLogger().severe("Terminal home teleport callback failed: " + rootMessage(callbackError));
                return null;
            }));
            return null;
        }).exceptionally(callbackError -> {
            plugin.getLogger().severe("Final home destination callback failed: " + rootMessage(callbackError));
            return null;
        }));
    }

    private void onActivity(long token, PlayerActivity activity) {
        if (!Bukkit.isPrimaryThread()) {
            onMain(() -> { onActivity(token, activity); return null; });
            return;
        }
        Attempt attempt = attempts.get(activity.playerId());
        if (attempt == null || attempt.token != token) return;
        Snapshot snapshot = config.get();
        Signal signal = activity.signal();
        if (!activityOwned(attempt.phase, signal)) return;
        Player player = Bukkit.getPlayer(activity.playerId());
        if (signal == Signal.QUIT) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.QUIT, false);
            return;
        }
        if (signal == Signal.DIED) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.DIED, false);
            return;
        }
        if (signal == Signal.WORLD_CHANGED) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.WORLD_CHANGED, true);
            return;
        }
        if (signal == Signal.DAMAGED && snapshot.cancelOnDamage()) {
            terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.DAMAGED, true);
            return;
        }
        if (signal == Signal.MOVED && snapshot.cancelOnMovement() && activity.to() != null) {
            if (attempt.origin.getWorld() == null || !attempt.origin.getWorld().getUID().equals(activity.to().worldId())) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.WORLD_CHANGED, true);
                return;
            }
            double dx = attempt.origin.getX() - activity.to().x();
            double dy = attempt.origin.getY() - activity.to().y();
            double dz = attempt.origin.getZ() - activity.to().z();
            double threshold = snapshot.movementThreshold();
            if (dx * dx + dy * dy + dz * dz > threshold * threshold) {
                terminate(attempt, player, HomeTeleportStatus.CANCELLED, HomeTeleportCancellationReason.MOVED, true);
            }
        }
    }

    static boolean activityOwned(Phase phase, Signal signal) {
        if (signal == Signal.QUIT || signal == Signal.DIED || signal == Signal.WORLD_CHANGED) return true;
        return (signal == Signal.MOVED || signal == Signal.DAMAGED) && activityCancellable(phase);
    }

    static boolean activityCancellable(Phase phase) {
        return phase != Phase.TELEPORTING;
    }

    private boolean canUse(Player player, Home home) {
        Snapshot snapshot = config.get();
        if (snapshot.allowUseAboveLimit()) return true;
        HomeLimitView limit = homes.limit(player);
        if (limit.unlimited()) return true;
        if (limit.limit() <= 0) return false;
        List<Home> ordered = homes.listCached(player.getUniqueId());
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).homeId().equals(home.homeId())) return i < limit.limit();
        }
        return false;
    }

    private void terminate(Attempt attempt, Player player, HomeTeleportStatus status,
                           HomeTeleportCancellationReason reason, boolean message) {
        if (!attempts.remove(attempt.playerId, attempt)) return;
        attempt.closeWatch();
        double refund = attempt.claimRefund();
        if (refund > 0.0D && !economy.refund(attempt.payer, refund)) {
            plugin.getLogger().severe("Vault did not confirm refund for home teleport attempt " + attempt.token);
        }
        if (player != null) {
            Bukkit.getPluginManager().callEvent(new PlexonHomeTeleportCancelledEvent(player, attempt.home.view(), reason));
            if (message) player.sendMessage(MiniMessage.miniMessage().deserialize("<red>"
                    + MiniMessage.miniMessage().escapeTags(cancelMessage(reason, status)) + "</red>"));
        }
        attempt.requestResult.complete(status);
    }

    private boolean coolingDown(Player player) {
        if (player.hasPermission("plexonhomes.teleport.cooldown.bypass")) return false;
        long seconds = config.get().cooldownSeconds();
        if (seconds <= 0) return false;
        Long start = cooldowns.get(player.getUniqueId());
        if (start == null) return false;
        if (System.nanoTime() - start >= seconds * 1_000_000_000L) {
            cooldowns.remove(player.getUniqueId(), start);
            return false;
        }
        return true;
    }

    private static World resolveWorld(Home home) {
        World world = Bukkit.getWorld(home.worldId());
        return world != null || home.worldName() == null ? world : Bukkit.getWorld(home.worldName());
    }

    private boolean owns(Attempt attempt) { return attempts.get(attempt.playerId) == attempt; }
    public boolean isPending(UUID playerId) { return attempts.containsKey(playerId); }
    public int pendingCount() { return attempts.size(); }
    public long inFlightCount() { return attempts.values().stream().filter(attempt -> attempt.phase == Phase.TELEPORTING).count(); }
    public int cooldownCount() { return cooldowns.size(); }

    private static String cancelMessage(HomeTeleportCancellationReason reason, HomeTeleportStatus status) {
        if (status == HomeTeleportStatus.LIMIT_RESTRICTED) return "Teleport cancelled because this home is above your current usable limit.";
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

    private <T> CompletableFuture<T> onMain(java.util.concurrent.Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) {
            try { return CompletableFuture.completedFuture(callable.call()); }
            catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { future.complete(callable.call()); }
                catch (Throwable throwable) { future.completeExceptionally(throwable); }
            });
        } catch (Throwable schedulingFailure) {
            future.completeExceptionally(schedulingFailure);
        }
        return future;
    }

    private static long saturatingDeadline(long now, int seconds) {
        long delta = seconds * 1_000_000_000L;
        if (now > 0L && Long.MAX_VALUE - now < delta) return Long.MAX_VALUE;
        return now + delta;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return String.valueOf(cursor.getMessage());
    }

    @Override public void close() {
        warmupCoordinator.cancel();
        for (Attempt attempt : List.copyOf(attempts.values())) {
            terminate(attempt, Bukkit.getPlayer(attempt.playerId), HomeTeleportStatus.CANCELLED,
                    HomeTeleportCancellationReason.PLUGIN_DISABLED, false);
        }
        attempts.clear();
        cooldowns.clear();
    }

    static final class Attempt {
        final long token;
        final UUID playerId;
        final OfflinePlayer payer;
        final Home home;
        final Location origin;
        final double fee;
        final CompletableFuture<HomeTeleportStatus> requestResult = new CompletableFuture<>();
        volatile Phase phase = Phase.RESOLVING;
        volatile long deadlineNanos;
        volatile Location destination;
        volatile WatchHandle watch;
        volatile double charged;
        volatile boolean refunded;

        Attempt(long token, UUID playerId, OfflinePlayer payer, Home home, Location origin, double fee) {
            this.token = token;
            this.playerId = playerId;
            this.payer = payer;
            this.home = home;
            this.origin = origin;
            this.fee = fee;
        }

        double claimRefund() {
            if (charged <= 0.0D || refunded) return 0.0D;
            refunded = true;
            return charged;
        }

        void closeWatch() {
            WatchHandle current = watch;
            watch = null;
            if (current != null) current.close();
        }
    }
}
