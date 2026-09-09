package com.plexon.homes.migration;

import com.plexon.homes.model.Home;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.util.HomeNames;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class EssentialsMigrationService {
    public record Candidate(UUID playerId, String sourceName, String targetId, String worldToken, double x, double y, double z, float yaw, float pitch) {}
    public record Report(int files, int players, int homes, int conflicts, int unresolvedWorlds, int imported, String fingerprint, String status) {}

    private final JavaPlugin plugin;
    private final HomeService homes;
    private final AtomicReference<Report> last = new AtomicReference<>(new Report(0,0,0,0,0,0,"","NOT_SCANNED"));
    private volatile List<Candidate> plan = List.of();

    public EssentialsMigrationService(JavaPlugin plugin, HomeService homes) { this.plugin = plugin; this.homes = homes; }
    public Report status() { return last.get(); }

    public CompletableFuture<Report> scan() {
        Path userdata = plugin.getDataFolder().toPath().getParent().resolve("Essentials").resolve("userdata");
        return CompletableFuture.supplyAsync(() -> parse(userdata));
    }

    private Report parse(Path userdata) {
        if (!Files.isDirectory(userdata)) {
            plan = List.of();
            Report report = new Report(0,0,0,0,0,0,"","SOURCE_NOT_FOUND"); last.set(report); return report;
        }
        List<Candidate> candidates = new ArrayList<>();
        int files = 0; int players = 0; int conflicts = 0;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.list(userdata)) {
                for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                    files++; byte[] bytes = Files.readAllBytes(path); digest.update(bytes);
                    String base = path.getFileName().toString().replaceFirst("\\.yml$", "");
                    UUID playerId;
                    try { playerId = UUID.fromString(base); } catch (IllegalArgumentException invalid) { continue; }
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
                    ConfigurationSection section = yaml.getConfigurationSection("homes");
                    if (section == null || section.getKeys(false).isEmpty()) continue;
                    players++;
                    Map<String,Integer> names = new LinkedHashMap<>();
                    for (String sourceName : section.getKeys(false)) {
                        String rawId = HomeNames.normalize(sourceName, 24).orElse("home");
                        int n = names.merge(rawId, 1, Integer::sum);
                        String targetId = n == 1 ? rawId : rawId + "-" + n;
                        if (n > 1) conflicts++;
                        String root = "homes." + sourceName + ".";
                        String world = yaml.getString(root + "world-name", yaml.getString(root + "world", ""));
                        candidates.add(new Candidate(playerId, sourceName, targetId, world, yaml.getDouble(root + "x"), yaml.getDouble(root + "y"), yaml.getDouble(root + "z"), (float) yaml.getDouble(root + "yaw"), (float) yaml.getDouble(root + "pitch")));
                    }
                }
            }
            plan = List.copyOf(candidates);
            Report report = new Report(files, players, candidates.size(), conflicts, 0, 0, HexFormat.of().formatHex(digest.digest()), "SCANNED"); last.set(report); return report;
        } catch (Exception exception) {
            throw new RuntimeException("Essentials home scan failed", exception);
        }
    }

    public CompletableFuture<Report> execute(boolean replaceExisting) {
        Report scanned = last.get();
        if (!scanned.status().equals("SCANNED") && !scanned.status().equals("PLANNED")) return CompletableFuture.completedFuture(scanned);
        CompletableFuture<Report> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Home> resolved = new ArrayList<>(); int unresolved = 0;
            long now = System.currentTimeMillis();
            for (Candidate candidate : plan) {
                World world = resolveWorld(candidate.worldToken());
                if (world == null || !finite(candidate.x()) || !finite(candidate.y()) || !finite(candidate.z())) { unresolved++; continue; }
                resolved.add(new Home(candidate.playerId(), candidate.targetId(), candidate.sourceName(), world.getUID(), world.getName(), candidate.x(), candidate.y(), candidate.z(), candidate.yaw(), candidate.pitch(), now, now, 1L));
            }
            final int unresolvedCount = unresolved;
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            java.util.concurrent.atomic.AtomicInteger imported = new java.util.concurrent.atomic.AtomicInteger();
            for (Home home : resolved) chain = chain.thenCompose(ignored -> homes.importHome(home, replaceExisting).thenAccept(ok -> { if (ok) imported.incrementAndGet(); }));
            chain.whenComplete((ignored, error) -> {
                Report report = new Report(scanned.files(), scanned.players(), scanned.homes(), scanned.conflicts(), unresolvedCount, imported.get(), scanned.fingerprint(), error == null ? "EXECUTED" : "FAILED");
                last.set(report); if (error == null) result.complete(report); else result.completeExceptionally(error);
            });
        });
        return result;
    }

    public Report markPlanned() {
        Report current = last.get();
        Report report = new Report(current.files(), current.players(), current.homes(), current.conflicts(), current.unresolvedWorlds(), current.imported(), current.fingerprint(), current.status().equals("SCANNED") ? "PLANNED" : current.status());
        last.set(report); return report;
    }

    private static World resolveWorld(String token) {
        if (token == null || token.isBlank()) return null;
        try { World byUuid = Bukkit.getWorld(UUID.fromString(token)); if (byUuid != null) return byUuid; } catch (IllegalArgumentException ignored) { }
        return Bukkit.getWorld(token);
    }
    private static boolean finite(double value) { return Double.isFinite(value); }
}
