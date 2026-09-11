package com.plexon.homes.migration;

import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.model.Home;
import com.plexon.homes.model.HomeIdentity;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.util.HomeNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Dedicated, non-destructive SetHome homes.yml migration provider.
 *
 * <p>The source file is read-only. Planning never mutates PlexonHomes. Execution imports only
 * records classified IMPORT and never replaces an existing name. Stable provider identities make
 * successful imports idempotent even if the imported home is later renamed in PlexonHomes.</p>
 */
public final class SetHomeMigrationService {
    public enum Action {
        IMPORT,
        SKIP_ALREADY_IMPORTED,
        SKIP_CONFLICT,
        QUARANTINE_INVALID_WORLD,
        QUARANTINE_INVALID_COORDINATE,
        QUARANTINE_INVALID_RECORD
    }

    public record Report(
            boolean sourceFound,
            String fingerprint,
            int players,
            int homes,
            int validRecords,
            int invalidRecords,
            int invalidCoordinates,
            int malformedRecords,
            int unavailableWorlds,
            int conflicts,
            int alreadyImported,
            int plannedImports,
            int imported,
            int executionRejected,
            String status) {
        public int quarantined() { return invalidRecords + unavailableWorlds; }
        public int skipped() { return conflicts + alreadyImported; }
    }

    enum ParseIssue { NONE, INVALID_COORDINATE, INVALID_RECORD }

    record SourceRecord(
            UUID ownerId,
            String sourceName,
            String nameKey,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            ParseIssue issue,
            String reason) {}

    record ParsedSource(
            boolean sourceFound,
            String fingerprint,
            int players,
            List<SourceRecord> records,
            boolean parseFailure,
            String parseFailureReason) {}

    record WorldRef(UUID worldId, String worldName) {}

    @FunctionalInterface
    interface WorldLookup {
        WorldRef resolve(String sourceWorldName);
    }

    interface TargetAccess {
        CompletableFuture<Void> prepare(Set<UUID> owners);
        Map<UUID, List<Home>> snapshot(Set<UUID> owners);
        CompletableFuture<Boolean> importHome(Home home);
    }

    @FunctionalInterface
    interface PlanExecutor {
        CompletableFuture<Plan> execute(Supplier<Plan> work);
    }

    record PlannedRecord(SourceRecord source, UUID migrationId, Home home, Action action, String reason) {}
    record Plan(Report report, List<PlannedRecord> records) {}

    private static final Report NOT_SCANNED = new Report(
            false, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NOT_SCANNED");

    private final Path source;
    private final IntSupplier maxNameLength;
    private final TargetAccess target;
    private final WorldLookup worlds;
    private final PlanExecutor planExecutor;
    private final AtomicReference<Report> last = new AtomicReference<>(NOT_SCANNED);
    private volatile Plan currentPlan = new Plan(NOT_SCANNED, List.of());
    private volatile String approvedFingerprint = "";

    public SetHomeMigrationService(JavaPlugin plugin, HomeService homes, Supplier<Snapshot> config) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(homes, "homes");
        Objects.requireNonNull(config, "config");
        Path plugins = plugin.getDataFolder().toPath().getParent();
        this.source = plugins.resolve("SetHome").resolve("homes.yml");
        this.maxNameLength = () -> config.get().maxNameLength();
        this.target = new TargetAccess() {
            @Override
            public CompletableFuture<Void> prepare(Set<UUID> owners) {
                CompletableFuture<?>[] loads = owners.stream()
                        .map(homes::ensureLoaded)
                        .toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(loads);
            }

            @Override
            public Map<UUID, List<Home>> snapshot(Set<UUID> owners) {
                Map<UUID, List<Home>> result = new HashMap<>();
                for (UUID owner : owners) result.put(owner, homes.listCached(owner));
                return Map.copyOf(result);
            }

            @Override
            public CompletableFuture<Boolean> importHome(Home home) {
                return homes.importHome(home, false);
            }
        };
        this.worlds = sourceWorldName -> {
            if (sourceWorldName == null || sourceWorldName.isBlank()) return null;
            World world = Bukkit.getWorld(sourceWorldName);
            Snapshot snapshot = config.get();
            if (world == null || !world.getName().equals(sourceWorldName) || !snapshot.canTeleportTo(world)) return null;
            return new WorldRef(world.getUID(), world.getName());
        };
        this.planExecutor = work -> onMain(plugin, work::get);
    }

    SetHomeMigrationService(
            Path source,
            IntSupplier maxNameLength,
            TargetAccess target,
            WorldLookup worlds,
            PlanExecutor planExecutor) {
        this.source = Objects.requireNonNull(source, "source");
        this.maxNameLength = Objects.requireNonNull(maxNameLength, "maxNameLength");
        this.target = Objects.requireNonNull(target, "target");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor");
    }

    public Report status() {
        return last.get();
    }

    /** Read-only source and destination scan. */
    public CompletableFuture<Report> scan() {
        approvedFingerprint = "";
        return buildPlan("SCANNED", true).thenApply(Plan::report);
    }

    /** Fresh read-only dry-run; this is the required approval boundary before execute. */
    public CompletableFuture<Report> plan() {
        return buildPlan("PLANNED", true).thenApply(plan -> {
            if ("PLANNED".equals(plan.report().status())) approvedFingerprint = plan.report().fingerprint();
            else approvedFingerprint = "";
            return plan.report();
        });
    }

    public CompletableFuture<Report> execute() {
        Plan approved = currentPlan;
        if (!"PLANNED".equals(approved.report().status()) || approvedFingerprint.isBlank()) {
            Report report = copy(approved.report(), "PLAN_REQUIRED", 0, 0);
            publish(new Plan(report, approved.records()));
            return CompletableFuture.completedFuture(report);
        }

        String expectedFingerprint = approvedFingerprint;
        return buildPlan("EXECUTION_READY", false).thenCompose(live -> {
            if (!"EXECUTION_READY".equals(live.report().status())) {
                publish(live);
                approvedFingerprint = "";
                return CompletableFuture.completedFuture(live.report());
            }
            if (!expectedFingerprint.equals(live.report().fingerprint())) {
                Report report = copy(live.report(), "SOURCE_CHANGED_REPLAN_REQUIRED", 0, 0);
                publish(new Plan(report, live.records()));
                approvedFingerprint = "";
                return CompletableFuture.completedFuture(report);
            }

            AtomicInteger imported = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (PlannedRecord record : live.records()) {
                if (record.action() != Action.IMPORT || record.home() == null) continue;
                chain = chain.thenCompose(ignored -> target.importHome(record.home())
                        .thenAccept(ok -> {
                            if (ok) imported.incrementAndGet();
                            else rejected.incrementAndGet();
                        }));
            }

            return chain.thenCompose(ignored -> buildPlan("EXECUTED", false))
                    .thenApply(post -> {
                        String status = expectedFingerprint.equals(post.report().fingerprint())
                                ? (rejected.get() == 0 ? "EXECUTED" : "EXECUTED_WITH_REJECTED")
                                : "EXECUTED_SOURCE_CHANGED_REPLAN_REQUIRED";
                        Report report = copy(post.report(), status, imported.get(), rejected.get());
                        publish(new Plan(report, post.records()));
                        approvedFingerprint = "";
                        return report;
                    });
        });
    }

    /** Fresh read-only post-execution verification. */
    public CompletableFuture<Report> verify() {
        approvedFingerprint = "";
        return buildPlan("VERIFIED", true).thenApply(Plan::report);
    }

    private CompletableFuture<Plan> buildPlan(String requestedStatus, boolean publish) {
        int maxLength = maxNameLength.getAsInt();
        return CompletableFuture.supplyAsync(() -> parseSource(source, maxLength))
                .thenCompose(parsed -> {
                    Set<UUID> owners = ownerIds(parsed);
                    return target.prepare(owners)
                            .thenCompose(ignored -> planExecutor.execute(
                                    () -> classify(parsed, target.snapshot(owners), worlds,
                                            System.currentTimeMillis(), requestedStatus)));
                })
                .thenApply(plan -> {
                    if (publish) publish(plan);
                    return plan;
                });
    }

    private static Set<UUID> ownerIds(ParsedSource parsed) {
        return parsed.records().stream()
                .map(SourceRecord::ownerId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void publish(Plan plan) {
        currentPlan = plan;
        last.set(plan.report());
    }

    private static <T> CompletableFuture<T> onMain(JavaPlugin plugin, Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(callable.call());
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(callable.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    static ParsedSource parseSource(Path source, int maxNameLength) {
        if (!Files.isRegularFile(source)) return new ParsedSource(false, "", 0, List.of(), false, "");
        try {
            byte[] bytes = Files.readAllBytes(source);
            String fingerprint = sha256(bytes);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException invalid) {
                return new ParsedSource(true, fingerprint, 0,
                        List.of(new SourceRecord(null, "", "", "", 0, 0, 0, 0, 0,
                                ParseIssue.INVALID_RECORD, "source-yaml-invalid")),
                        true, "source-yaml-invalid");
            }

            List<SourceRecord> records = new ArrayList<>();
            int players = 0;
            for (String ownerToken : yaml.getKeys(false)) {
                players++;
                ConfigurationSection player = yaml.getConfigurationSection(ownerToken);
                if (player == null) {
                    records.add(invalidRecord(null, "", "player-section-is-not-a-mapping"));
                    continue;
                }

                UUID ownerId;
                try {
                    ownerId = UUID.fromString(ownerToken);
                } catch (IllegalArgumentException invalidUuid) {
                    ownerId = null;
                }

                Set<String> homeNames = player.getKeys(false);
                if (homeNames.isEmpty() && ownerId == null) {
                    records.add(invalidRecord(null, "", "player-uuid-invalid"));
                    continue;
                }
                for (String sourceName : homeNames) {
                    if (ownerId == null) {
                        records.add(invalidRecord(null, sourceName, "player-uuid-invalid"));
                        continue;
                    }
                    records.add(parseHome(player, ownerId, sourceName, maxNameLength));
                }
            }
            return new ParsedSource(true, fingerprint, players, List.copyOf(records), false, "");
        } catch (Exception error) {
            throw new RuntimeException("SetHome scan failed", error);
        }
    }

    private static SourceRecord parseHome(
            ConfigurationSection player,
            UUID ownerId,
            String sourceName,
            int maxNameLength) {
        if (sourceName == null || sourceName.isBlank() || !sourceName.equals(sourceName.trim())) {
            return invalidRecord(ownerId, sourceName, "home-name-invalid");
        }
        var normalized = HomeNames.normalize(sourceName, maxNameLength);
        if (normalized.isEmpty()) return invalidRecord(ownerId, sourceName, "home-name-not-supported-by-plexonhomes");

        ConfigurationSection home = player.getConfigurationSection(sourceName);
        if (home == null) return invalidRecord(ownerId, sourceName, "home-record-is-not-a-mapping");

        Object rawWorld = home.get("world");
        if (!(rawWorld instanceof String worldName) || worldName.isBlank() || !worldName.equals(worldName.trim())) {
            return invalidRecord(ownerId, sourceName, "world-is-missing-or-invalid");
        }

        Double x = finiteNumber(home.get("x"));
        Double y = finiteNumber(home.get("y"));
        Double z = finiteNumber(home.get("z"));
        Double yawValue = finiteNumber(home.get("yaw"));
        Double pitchValue = finiteNumber(home.get("pitch"));
        if (x == null || y == null || z == null || yawValue == null || pitchValue == null) {
            return invalidCoordinate(ownerId, sourceName, normalized.get(), worldName,
                    "coordinate-is-missing-nonnumeric-or-nonfinite");
        }
        float yaw = yawValue.floatValue();
        float pitch = pitchValue.floatValue();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return invalidCoordinate(ownerId, sourceName, normalized.get(), worldName,
                    "yaw-or-pitch-exceeds-supported-precision");
        }
        return new SourceRecord(ownerId, sourceName, normalized.get(), worldName,
                x, y, z, yaw, pitch, ParseIssue.NONE, "");
    }

    private static SourceRecord invalidRecord(UUID ownerId, String sourceName, String reason) {
        return new SourceRecord(ownerId, sourceName == null ? "" : sourceName, "", "",
                0, 0, 0, 0, 0, ParseIssue.INVALID_RECORD, reason);
    }

    private static SourceRecord invalidCoordinate(
            UUID ownerId, String sourceName, String nameKey, String worldName, String reason) {
        return new SourceRecord(ownerId, sourceName, nameKey, worldName,
                0, 0, 0, 0, 0, ParseIssue.INVALID_COORDINATE, reason);
    }

    private static Double finiteNumber(Object raw) {
        if (!(raw instanceof Number number)) return null;
        double value = number.doubleValue();
        return Double.isFinite(value) ? value : null;
    }

    static Plan classify(
            ParsedSource parsed,
            Map<UUID, List<Home>> targetHomes,
            WorldLookup worlds,
            long now,
            String requestedStatus) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(targetHomes, "targetHomes");
        Objects.requireNonNull(worlds, "worlds");

        String status;
        if (!parsed.sourceFound()) status = "SOURCE_NOT_FOUND";
        else if (parsed.parseFailure()) status = "SOURCE_INVALID";
        else status = requestedStatus;

        Map<String, Integer> sourceNameCounts = new HashMap<>();
        for (SourceRecord record : parsed.records()) {
            if (record.issue() != ParseIssue.NONE || record.ownerId() == null) continue;
            sourceNameCounts.merge(record.ownerId() + "\n" + record.nameKey(), 1, Integer::sum);
        }

        List<PlannedRecord> decisions = new ArrayList<>();
        int invalidCoordinates = 0;
        int malformed = 0;
        int unavailableWorlds = 0;
        int conflicts = 0;
        int alreadyImported = 0;
        int imports = 0;

        for (SourceRecord source : parsed.records()) {
            if (source.issue() == ParseIssue.INVALID_COORDINATE) {
                invalidCoordinates++;
                decisions.add(new PlannedRecord(source, null, null,
                        Action.QUARANTINE_INVALID_COORDINATE, source.reason()));
                continue;
            }
            if (source.issue() == ParseIssue.INVALID_RECORD || source.ownerId() == null) {
                malformed++;
                decisions.add(new PlannedRecord(source, null, null,
                        Action.QUARANTINE_INVALID_RECORD, source.reason()));
                continue;
            }

            UUID migrationId = HomeIdentity.migrationStableId(source.ownerId(), "sethome", source.sourceName());
            List<Home> existing = targetHomes.getOrDefault(source.ownerId(), List.of());
            Home byIdentity = existing.stream()
                    .filter(home -> home.homeId().equals(migrationId))
                    .findFirst()
                    .orElse(null);
            if (byIdentity != null) {
                alreadyImported++;
                decisions.add(new PlannedRecord(source, migrationId, null,
                        Action.SKIP_ALREADY_IMPORTED, "stable-migration-identity-present"));
                continue;
            }

            WorldRef world = worlds.resolve(source.worldName());
            if (world == null) {
                unavailableWorlds++;
                decisions.add(new PlannedRecord(source, migrationId, null,
                        Action.QUARANTINE_INVALID_WORLD, "world-unavailable-or-blocked"));
                continue;
            }

            String sourceKey = source.ownerId() + "\n" + source.nameKey();
            boolean duplicateSourceName = sourceNameCounts.getOrDefault(sourceKey, 0) > 1;
            boolean targetNameConflict = existing.stream().anyMatch(home -> home.nameKey().equals(source.nameKey()));
            if (duplicateSourceName || targetNameConflict) {
                conflicts++;
                decisions.add(new PlannedRecord(source, migrationId, null,
                        Action.SKIP_CONFLICT, duplicateSourceName
                                ? "source-name-collides-after-plexon-normalization"
                                : "plexonhomes-name-already-exists"));
                continue;
            }

            Home candidate = new Home(
                    source.ownerId(),
                    migrationId,
                    source.nameKey(),
                    source.sourceName(),
                    world.worldId(),
                    world.worldName(),
                    source.x(),
                    source.y(),
                    source.z(),
                    source.yaw(),
                    source.pitch(),
                    now,
                    now,
                    1L);
            imports++;
            decisions.add(new PlannedRecord(source, migrationId, candidate, Action.IMPORT, "ready"));
        }

        int invalid = invalidCoordinates + malformed;
        int total = parsed.records().size();
        int valid = Math.max(0, total - invalid);
        Report report = new Report(
                parsed.sourceFound(),
                parsed.fingerprint(),
                parsed.players(),
                total,
                valid,
                invalid,
                invalidCoordinates,
                malformed,
                unavailableWorlds,
                conflicts,
                alreadyImported,
                imports,
                0,
                0,
                status);
        return new Plan(report, List.copyOf(decisions));
    }

    private static Report copy(Report base, String status, int imported, int rejected) {
        return new Report(
                base.sourceFound(),
                base.fingerprint(),
                base.players(),
                base.homes(),
                base.validRecords(),
                base.invalidRecords(),
                base.invalidCoordinates(),
                base.malformedRecords(),
                base.unavailableWorlds(),
                base.conflicts(),
                base.alreadyImported(),
                base.plannedImports(),
                imported,
                rejected,
                status);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
