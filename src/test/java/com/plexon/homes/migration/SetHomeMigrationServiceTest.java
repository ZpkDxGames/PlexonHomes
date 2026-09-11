package com.plexon.homes.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plexon.homes.migration.SetHomeMigrationService.Action;
import com.plexon.homes.migration.SetHomeMigrationService.ParsedSource;
import com.plexon.homes.migration.SetHomeMigrationService.Plan;
import com.plexon.homes.migration.SetHomeMigrationService.TargetAccess;
import com.plexon.homes.migration.SetHomeMigrationService.WorldRef;
import com.plexon.homes.model.Home;
import com.plexon.homes.model.HomeIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetHomeMigrationServiceTest {
    private static final UUID WORLD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @TempDir Path temp;

    @Test
    void parsesOnePlayerOneHome() throws Exception {
        UUID owner = UUID.randomUUID();
        Path source = write(player(owner, home("home", "world", "1", "64", "2", "10", "20")));
        ParsedSource parsed = SetHomeMigrationService.parseSource(source, 24);
        assertTrue(parsed.sourceFound());
        assertEquals(1, parsed.players());
        assertEquals(1, parsed.records().size());
        assertEquals("home", parsed.records().getFirst().sourceName());
    }

    @Test
    void parsesOnePlayerMultipleHomes() throws Exception {
        UUID owner = UUID.randomUUID();
        Path source = write(player(owner,
                home("home", "world", "1", "64", "2", "0", "0"),
                home("mine", "world", "3", "65", "4", "90", "5")));
        ParsedSource parsed = SetHomeMigrationService.parseSource(source, 24);
        assertEquals(1, parsed.players());
        assertEquals(2, parsed.records().size());
    }

    @Test
    void parsesMultiplePlayers() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Path source = write(player(first, home("home", "world", "1", "64", "2", "0", "0"))
                + player(second, home("base", "world", "4", "70", "8", "15", "-5")));
        ParsedSource parsed = SetHomeMigrationService.parseSource(source, 24);
        assertEquals(2, parsed.players());
        assertEquals(2, parsed.records().size());
    }

    @Test
    void preservesYawAndPitch() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "world", "1", "64", "2", "123.5", "-44.25"))), 24);
        Plan plan = plan(parsed, Map.of(), world("world"));
        Home imported = plan.records().getFirst().home();
        assertEquals(123.5F, imported.yaw());
        assertEquals(-44.25F, imported.pitch());
    }

    @Test
    void preservesDecimalCoordinates() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "world", "1.123456789", "64.987654321", "-2.000000001", "0", "0"))), 24);
        Home imported = plan(parsed, Map.of(), world("world")).records().getFirst().home();
        assertEquals(1.123456789D, imported.x());
        assertEquals(64.987654321D, imported.y());
        assertEquals(-2.000000001D, imported.z());
    }

    @Test
    void canonicalDuplicateHomeNamesAreConflictsNotRenamed() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("Home", "world", "1", "64", "2", "0", "0"),
                home("home", "world", "3", "64", "4", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(), world("world"));
        assertEquals(2, plan.report().conflicts());
        assertTrue(plan.records().stream().allMatch(record -> record.action() == Action.SKIP_CONFLICT));
    }

    @Test
    void stableProviderIdentityReportsAlreadyImportedEvenAfterRename() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stable = HomeIdentity.migrationStableId(owner, "sethome", "home");
        Home renamed = existing(owner, stable, "renamed", "Renamed");
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "world", "1", "64", "2", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(owner, List.of(renamed)), world("world"));
        assertEquals(1, plan.report().alreadyImported());
        assertEquals(Action.SKIP_ALREADY_IMPORTED, plan.records().getFirst().action());
    }

    @Test
    void unavailableWorldIsQuarantined() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "missing_world", "1", "64", "2", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(), ignored -> null);
        assertEquals(1, plan.report().unavailableWorlds());
        assertEquals(Action.QUARANTINE_INVALID_WORLD, plan.records().getFirst().action());
    }

    @Test
    void invalidNumericValueIsQuarantined() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "world", "not-a-number", "64", "2", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(), world("world"));
        assertEquals(1, plan.report().invalidCoordinates());
        assertEquals(Action.QUARANTINE_INVALID_COORDINATE, plan.records().getFirst().action());
    }

    @Test
    void malformedRecordIsQuarantined() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(owner + ":\n  home: broken\n"), 24);
        Plan plan = plan(parsed, Map.of(), world("world"));
        assertEquals(1, plan.report().malformedRecords());
        assertEquals(Action.QUARANTINE_INVALID_RECORD, plan.records().getFirst().action());
    }

    @Test
    void emptyFileProducesEmptyFoundSource() throws Exception {
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(""), 24);
        assertTrue(parsed.sourceFound());
        assertEquals(0, parsed.players());
        assertEquals(0, parsed.records().size());
    }

    @Test
    void missingFileIsReportedWithoutCreatingIt() {
        Path source = temp.resolve("missing.yml");
        ParsedSource parsed = SetHomeMigrationService.parseSource(source, 24);
        assertFalse(parsed.sourceFound());
        assertFalse(Files.exists(source));
    }

    @Test
    void secondIdenticalImportIsIdempotent() throws Exception {
        UUID owner = UUID.randomUUID();
        Path source = write(player(owner, home("home", "world", "1", "64", "2", "5", "6")));
        FakeTarget target = new FakeTarget();
        SetHomeMigrationService service = service(source, target, world("world"));

        assertEquals(1, service.plan().join().plannedImports());
        var first = service.execute().join();
        assertEquals(1, first.imported());
        assertEquals(1, first.alreadyImported());
        assertEquals(1, target.homes(owner).size());

        assertEquals(1, service.plan().join().alreadyImported());
        var second = service.execute().join();
        assertEquals(0, second.imported());
        assertEquals(1, second.alreadyImported());
        assertEquals(1, target.homes(owner).size());
    }

    @Test
    void existingPlexonNativeHomesRemainUntouched() throws Exception {
        UUID owner = UUID.randomUUID();
        Home nativeHome = existing(owner, UUID.randomUUID(), "native", "Native");
        FakeTarget target = new FakeTarget();
        target.add(nativeHome);
        Path source = write(player(owner, home("migrated", "world", "9", "70", "9", "0", "0")));
        SetHomeMigrationService service = service(source, target, world("world"));

        service.plan().join();
        service.execute().join();

        Home stillNative = target.homes(owner).stream().filter(home -> home.homeId().equals(nativeHome.homeId())).findFirst().orElseThrow();
        assertSame(nativeHome, stillNative);
        assertEquals(2, target.homes(owner).size());
    }

    @Test
    void validRecordImportsWhenAnotherRecordIsQuarantined() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("good", "world", "1", "64", "2", "0", "0"),
                home("bad", "missing", "3", "65", "4", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(), name -> name.equals("world") ? new WorldRef(WORLD_ID, "world") : null);
        assertEquals(1, plan.report().plannedImports());
        assertEquals(1, plan.report().unavailableWorlds());
        assertTrue(plan.records().stream().anyMatch(record -> record.action() == Action.IMPORT));
        assertTrue(plan.records().stream().anyMatch(record -> record.action() == Action.QUARANTINE_INVALID_WORLD));
    }

    @Test
    void existingNameConflictIsSkippedWithoutOverwrite() throws Exception {
        UUID owner = UUID.randomUUID();
        Home nativeHome = existing(owner, UUID.randomUUID(), "home", "Home");
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("home", "world", "1", "64", "2", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(owner, List.of(nativeHome)), world("world"));
        assertEquals(1, plan.report().conflicts());
        assertEquals(Action.SKIP_CONFLICT, plan.records().getFirst().action());
    }

    @Test
    void targetUnsupportedHomeNameIsQuarantinedInsteadOfRenamed() throws Exception {
        UUID owner = UUID.randomUUID();
        ParsedSource parsed = SetHomeMigrationService.parseSource(write(player(owner,
                home("my home", "world", "1", "64", "2", "0", "0"))), 24);
        Plan plan = plan(parsed, Map.of(), world("world"));
        assertEquals(1, plan.report().malformedRecords());
        assertEquals(Action.QUARANTINE_INVALID_RECORD, plan.records().getFirst().action());
    }

    private SetHomeMigrationService service(Path source, FakeTarget target, SetHomeMigrationService.WorldLookup worlds) {
        return new SetHomeMigrationService(source, () -> 24, target, worlds,
                work -> CompletableFuture.completedFuture(work.get()));
    }

    private static Plan plan(ParsedSource parsed, Map<UUID, List<Home>> existing, SetHomeMigrationService.WorldLookup worlds) {
        return SetHomeMigrationService.classify(parsed, existing, worlds, 1_700_000_000_000L, "PLANNED");
    }

    private SetHomeMigrationService.WorldLookup world(String expected) {
        return name -> expected.equals(name) ? new WorldRef(WORLD_ID, expected) : null;
    }

    private Path write(String contents) throws Exception {
        Path source = temp.resolve("homes.yml");
        Files.writeString(source, contents);
        return source;
    }

    private static String player(UUID owner, String... homes) {
        return owner + ":\n" + String.join("", homes);
    }

    private static String home(String name, String world, String x, String y, String z, String yaw, String pitch) {
        return "  " + name + ":\n"
                + "    world: " + world + "\n"
                + "    x: " + x + "\n"
                + "    y: " + y + "\n"
                + "    z: " + z + "\n"
                + "    yaw: " + yaw + "\n"
                + "    pitch: " + pitch + "\n";
    }

    private static Home existing(UUID owner, UUID homeId, String key, String display) {
        return new Home(owner, homeId, key, display, WORLD_ID, "world",
                1.0D, 64.0D, 2.0D, 0.0F, 0.0F, 1L, 1L, 1L);
    }

    private static final class FakeTarget implements TargetAccess {
        private final Map<UUID, List<Home>> homes = new HashMap<>();

        @Override public CompletableFuture<Void> prepare(Set<UUID> owners) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public Map<UUID, List<Home>> snapshot(Set<UUID> owners) {
            Map<UUID, List<Home>> result = new HashMap<>();
            for (UUID owner : owners) result.put(owner, List.copyOf(homes.getOrDefault(owner, List.of())));
            return Map.copyOf(result);
        }

        @Override public CompletableFuture<Boolean> importHome(Home home) {
            List<Home> current = homes.computeIfAbsent(home.ownerId(), ignored -> new ArrayList<>());
            boolean idConflict = current.stream().anyMatch(existing -> existing.homeId().equals(home.homeId()));
            boolean nameConflict = current.stream().anyMatch(existing -> existing.nameKey().equals(home.nameKey()));
            if (idConflict || nameConflict) return CompletableFuture.completedFuture(false);
            current.add(home);
            return CompletableFuture.completedFuture(true);
        }

        void add(Home home) {
            homes.computeIfAbsent(home.ownerId(), ignored -> new ArrayList<>()).add(home);
        }

        List<Home> homes(UUID owner) {
            return homes.getOrDefault(owner, List.of());
        }
    }
}
