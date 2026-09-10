package com.plexon.homes.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.plexon.homes.model.Home;
import com.plexon.homes.model.HomeIdentity;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeRepositoryMigrationTest {
    @TempDir Path temp;

    @Test void freshDatabaseStartsAtSchemaTwo() {
        try (var repository = new HomeRepository(temp.resolve("fresh.db"))) { repository.ready().join(); assertEquals(2, repository.schemaVersion()); assertTrue(repository.loadHomes(UUID.randomUUID()).join().isEmpty()); }
    }

    @Test void migratesPublishedV1ShapeAndPreservesFields() throws Exception {
        Path db = temp.resolve("legacy.db"); UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE homes(owner_uuid TEXT NOT NULL,home_id TEXT NOT NULL,display_name TEXT NOT NULL,world_uuid TEXT NOT NULL,world_name TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(owner_uuid,home_id))");
            statement.execute("CREATE TABLE players(player_uuid TEXT PRIMARY KEY,last_name TEXT,updated_at INTEGER)"); statement.execute("CREATE TABLE migration_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            try (var ps = connection.prepareStatement("INSERT INTO homes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, owner.toString()); ps.setString(2, "home"); ps.setString(3, "Home"); ps.setString(4, world.toString()); ps.setString(5, "survival");
                ps.setDouble(6, 1.25); ps.setDouble(7, 70); ps.setDouble(8, -3.5); ps.setFloat(9, 90); ps.setFloat(10, 5); ps.setLong(11, 10); ps.setLong(12, 20); ps.setLong(13, 7); ps.executeUpdate();
            }
        }
        try (var repository = new HomeRepository(db)) {
            repository.ready().join(); assertEquals(2, repository.schemaVersion()); var homes = repository.loadHomes(owner).join(); assertEquals(1, homes.size()); Home migrated = homes.getFirst();
            assertEquals(HomeIdentity.legacyStableId(owner, "home"), migrated.homeId()); assertEquals("home", migrated.nameKey()); assertEquals("Home", migrated.displayName()); assertEquals(world, migrated.worldId()); assertEquals(7, migrated.revision());
        }
        assertTrue(java.nio.file.Files.isDirectory(temp.resolve("backups"))); assertTrue(java.nio.file.Files.list(temp.resolve("backups")).anyMatch(path -> path.getFileName().toString().startsWith("homes-schema-v1-")));
    }

    @Test void v2RestartIsIdempotent() throws Exception {
        Path db = temp.resolve("restart.db"); UUID owner = UUID.randomUUID(); Home home = new Home(owner, UUID.randomUUID(), "mine", "Mine", UUID.randomUUID(), "world", 1, 65, 2, 0, 0, 1, 1, 1);
        try (var first = new HomeRepository(db)) { first.ready().join(); first.upsert(home).join(); }
        try (var second = new HomeRepository(db)) { second.ready().join(); assertEquals(home.homeId(), second.loadHomes(owner).join().getFirst().homeId()); assertEquals(2, second.schemaVersion()); }
    }

    @Test void futureSchemaFailsClosed() throws Exception {
        Path db = temp.resolve("future.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)"); statement.execute("INSERT INTO migration_meta VALUES('schema_version','99')");
        }
        try (var repository = new HomeRepository(db)) { CompletionException error = assertThrows(CompletionException.class, () -> repository.ready().join()); assertTrue(error.getCause().getMessage().contains("initialize homes database")); }
    }

    @Test void duplicateNameForDifferentStableIdsIsRejected() {
        Path db = temp.resolve("unique.db"); UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        Home firstHome = new Home(owner, UUID.randomUUID(), "home", "Home", world, "world", 1, 64, 1, 0, 0, 1, 1, 1);
        Home secondHome = new Home(owner, UUID.randomUUID(), "home", "Another", world, "world", 2, 64, 2, 0, 0, 1, 1, 1);
        try (var repository = new HomeRepository(db)) { repository.ready().join(); repository.upsert(firstHome).join(); assertThrows(CompletionException.class, () -> repository.upsert(secondHome).join()); }
    }

    @Test void malformedNonFiniteRecordIsRejectedBeforeWrite() {
        Path db = temp.resolve("malformed.db"); UUID owner = UUID.randomUUID(); Home bad = new Home(owner, UUID.randomUUID(), "home", "Home", UUID.randomUUID(), "world", Double.NaN, 64, 1, 0, 0, 1, 1, 1);
        try (var repository = new HomeRepository(db)) { repository.ready().join(); assertThrows(CompletionException.class, () -> repository.upsert(bad).join()); }
    }
}
