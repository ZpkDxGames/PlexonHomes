package com.plexon.homes.service;

import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.model.Home;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

public final class SafeTeleportService {
    private static final Set<Material> HAZARDS = Set.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW);

    private final JavaPlugin plugin;
    private final Supplier<Snapshot> config;

    public SafeTeleportService(JavaPlugin plugin, Supplier<Snapshot> config) {
        this.plugin = plugin;
        this.config = config;
    }

    public CompletableFuture<Optional<Location>> resolve(Home home) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Safe destination resolution must start on the primary thread");
        World world = Bukkit.getWorld(home.worldId());
        if (world == null && home.worldName() != null) world = Bukkit.getWorld(home.worldName());
        if (world == null) return CompletableFuture.completedFuture(Optional.empty());

        Location base = home.toLocation(world);
        int chunkX = base.getBlockX() >> 4;
        int chunkZ = base.getBlockZ() >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) return CompletableFuture.completedFuture(findSafe(base));

        World destinationWorld = world;
        return world.getChunkAtAsync(chunkX, chunkZ, true)
                .thenCompose(chunk -> onMain(() -> findSafe(home.toLocation(destinationWorld))));
    }

    public boolean isSafe(Location location) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block safety checks must run on the primary thread");
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        if (!finite(location.getX()) || !finite(location.getY()) || !finite(location.getZ())) return false;
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight() - 1) return false;
        if (!world.getWorldBorder().isInside(location)) return false;
        if (!config.get().safeTeleport()) return true;

        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        Block support = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        if (HAZARDS.contains(feet.getType()) || HAZARDS.contains(head.getType()) || HAZARDS.contains(support.getType())) return false;
        if (!occupiable(feet) || !occupiable(head)) return false;
        return support.getType().isSolid();
    }

    private Optional<Location> findSafe(Location base) {
        if (isSafe(base)) return Optional.of(base);
        Snapshot snapshot = config.get();
        if (!snapshot.safeTeleport() || !snapshot.searchNearby()) return Optional.empty();

        for (int dy = 1; dy <= snapshot.verticalRadius(); dy++) {
            Location up = base.clone().add(0, dy, 0);
            if (isSafe(up)) return Optional.of(up);
            Location down = base.clone().add(0, -dy, 0);
            if (isSafe(down)) return Optional.of(down);
        }
        for (int radius = 1; radius <= snapshot.horizontalRadius(); radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -snapshot.verticalRadius(); dy <= snapshot.verticalRadius(); dy++) {
                        Location candidate = base.clone().add(dx, dy, dz);
                        if (isSafe(candidate)) return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean occupiable(Block block) {
        Material material = block.getType();
        if (material == Material.WATER) return true;
        return block.isPassable() && material != Material.LAVA;
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(supplier.get());
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { result.complete(supplier.get()); }
            catch (Throwable throwable) { result.completeExceptionally(throwable); }
        });
        return result;
    }

    private static boolean finite(double value) { return Double.isFinite(value); }
}
