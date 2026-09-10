package com.plexon.homes.persistence;

import com.plexon.homes.model.Home;
import com.plexon.homes.model.HomeIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class HomeRepository implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;
    private final Path database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("PlexonHomes-SQLite-Writer").factory());
    private final ExecutorService queries = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("PlexonHomes-SQLite-Query-", 0).factory());
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicInteger schemaVersion = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ready;

    public HomeRepository(Path database) {
        this.database = database;
        this.ready = CompletableFuture.runAsync(this::initializeDatabase, writer);
    }

    public CompletableFuture<Void> ready() { return ready; }
    public long writes() { return writes.get(); }
    public long failures() { return failures.get(); }
    public int schemaVersion() { return schemaVersion.get(); }

    public CompletableFuture<List<Home>> loadHomes(UUID owner) {
        return ready.thenCompose(ignored -> submitQuery(() -> {
            List<Home> homes = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                    "SELECT owner_uuid,home_uuid,name_key,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision FROM homes WHERE owner_uuid=? ORDER BY created_at ASC")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) homes.add(read(rs)); }
                return List.copyOf(homes);
            }
        }));
    }

    public CompletableFuture<Void> upsert(Home home) {
        return write(() -> {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO homes(owner_uuid,home_uuid,name_key,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(owner_uuid,home_uuid) DO UPDATE SET name_key=excluded.name_key,display_name=excluded.display_name,world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,updated_at=excluded.updated_at,revision=excluded.revision")) {
                bind(ps, home); ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> delete(UUID owner, UUID homeId) {
        return write(() -> {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("DELETE FROM homes WHERE owner_uuid=? AND home_uuid=?")) {
                ps.setString(1, owner.toString()); ps.setString(2, homeId.toString()); ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> rememberPlayer(UUID uuid, String name) {
        return write(() -> {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO players(player_uuid,last_name,updated_at) VALUES(?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name,updated_at=excluded.updated_at")) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Path> backup(Path directory) {
        return ready.thenCompose(ignored -> CompletableFuture.supplyAsync(() -> backupDirect(directory, "manual"), writer));
    }

    private void initializeDatabase() {
        try {
            Files.createDirectories(database.getParent());
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS migration_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS players(player_uuid TEXT PRIMARY KEY,last_name TEXT,updated_at INTEGER)");
            }
            int marked = readSchemaMarker();
            if (marked > SCHEMA_VERSION) throw new IllegalStateException("Database schema " + marked + " is newer than supported schema " + SCHEMA_VERSION);
            if (!tableExists("homes")) {
                try (Connection connection = open(); Statement statement = connection.createStatement()) { createV2(statement, "homes"); }
                writeSchemaMarker(SCHEMA_VERSION); schemaVersion.set(SCHEMA_VERSION); return;
            }
            Set<String> columns = homeColumns();
            boolean v2Shape = columns.contains("home_uuid") && columns.contains("name_key");
            if (v2Shape) {
                if (marked != 0 && marked != SCHEMA_VERSION) throw new IllegalStateException("Schema marker does not match v2 homes table");
                writeSchemaMarker(SCHEMA_VERSION); schemaVersion.set(SCHEMA_VERSION); return;
            }
            if (!columns.contains("home_id")) throw new IllegalStateException("Unrecognized homes table; refusing destructive migration");
            if (marked > 1) throw new IllegalStateException("Schema marker is incompatible with legacy homes table");
            migrateV1ToV2();
            schemaVersion.set(SCHEMA_VERSION);
        } catch (Exception exception) {
            failures.incrementAndGet(); throw new RuntimeException("Unable to initialize homes database", exception);
        }
    }

    private void migrateV1ToV2() throws Exception {
        backupDirect(database.getParent().resolve("backups"), "schema-v1");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS homes_v2_migration");
                createV2(statement, "homes_v2_migration");
            }
            int copied = 0;
            try (Statement read = connection.createStatement(); ResultSet rs = read.executeQuery(
                    "SELECT owner_uuid,home_id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision FROM homes ORDER BY owner_uuid,created_at" );
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO homes_v2_migration(owner_uuid,home_uuid,name_key,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString(1));
                    String legacyKey = rs.getString(2);
                    Home home = new Home(owner, HomeIdentity.legacyStableId(owner, legacyKey), legacyKey, rs.getString(3),
                            UUID.fromString(rs.getString(4)), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                            rs.getFloat(9), rs.getFloat(10), rs.getLong(11), rs.getLong(12), rs.getLong(13));
                    validateStored(home); bind(insert, home); insert.addBatch(); copied++;
                }
                insert.executeBatch();
            }
            int sourceCount;
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM homes")) { rs.next(); sourceCount = rs.getInt(1); }
            if (sourceCount != copied) throw new IllegalStateException("Legacy migration row count mismatch");
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE homes");
                statement.execute("ALTER TABLE homes_v2_migration RENAME TO homes");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS homes_owner_name ON homes(owner_uuid,name_key)");
            }
            try (PreparedStatement marker = connection.prepareStatement("INSERT INTO migration_meta(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                marker.setString(1, Integer.toString(SCHEMA_VERSION)); marker.executeUpdate();
            }
            connection.commit();
        }
    }

    private static void createV2(Statement statement, String table) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS " + table + "(owner_uuid TEXT NOT NULL,home_uuid TEXT NOT NULL,name_key TEXT NOT NULL,display_name TEXT NOT NULL,world_uuid TEXT NOT NULL,world_name TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(owner_uuid,home_uuid),UNIQUE(owner_uuid,name_key))");
    }

    private int readSchemaMarker() throws SQLException {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("SELECT value FROM migration_meta WHERE key='schema_version'"); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return 0;
            try { return Integer.parseInt(rs.getString(1)); }
            catch (NumberFormatException bad) { throw new IllegalStateException("Invalid database schema marker", bad); }
        }
    }

    private void writeSchemaMarker(int version) throws SQLException {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("INSERT INTO migration_meta(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, Integer.toString(version)); ps.executeUpdate();
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private Set<String> homeColumns() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = open(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(homes)")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        return columns;
    }

    private Path backupDirect(Path directory, String reason) {
        try {
            Files.createDirectories(directory);
            try (Connection connection = open(); Statement statement = connection.createStatement()) { statement.execute("PRAGMA wal_checkpoint(FULL)"); }
            Path target = directory.resolve("homes-" + reason + "-" + Instant.now().toEpochMilli() + ".db");
            if (Files.exists(database)) Files.copy(database, target, StandardCopyOption.REPLACE_EXISTING); else Files.createFile(target);
            return target;
        } catch (Exception exception) { failures.incrementAndGet(); throw new RuntimeException("Unable to back up homes database", exception); }
    }

    private CompletableFuture<Void> write(SqlAction action) {
        return ready.thenCompose(ignored -> {
            if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Repository is closed"));
            return CompletableFuture.runAsync(() -> {
                try { action.run(); writes.incrementAndGet(); }
                catch (Exception exception) { failures.incrementAndGet(); throw new RuntimeException(exception); }
            }, writer);
        });
    }

    private <T> CompletableFuture<T> submitQuery(SqlSupplier<T> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Repository is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try { return action.get(); }
            catch (Exception exception) { failures.incrementAndGet(); throw new RuntimeException(exception); }
        }, queries);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static Home read(ResultSet rs) throws SQLException {
        Home home = new Home(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                UUID.fromString(rs.getString(5)), rs.getString(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                rs.getFloat(10), rs.getFloat(11), rs.getLong(12), rs.getLong(13), rs.getLong(14));
        validateStored(home); return home;
    }

    private static void validateStored(Home home) throws SQLException {
        if (home.ownerId() == null || home.homeId() == null || home.worldId() == null || home.nameKey() == null || home.nameKey().isBlank()
                || home.worldName() == null || home.worldName().isBlank() || !Double.isFinite(home.x()) || !Double.isFinite(home.y()) || !Double.isFinite(home.z())
                || !Float.isFinite(home.yaw()) || !Float.isFinite(home.pitch()) || home.revision() < 1L) {
            throw new SQLException("Malformed persisted home " + (home.homeId() == null ? "unknown" : home.homeId()));
        }
    }

    private static void bind(PreparedStatement ps, Home home) throws SQLException {
        validateStored(home);
        ps.setString(1, home.ownerId().toString()); ps.setString(2, home.homeId().toString()); ps.setString(3, home.nameKey()); ps.setString(4, home.displayName());
        ps.setString(5, home.worldId().toString()); ps.setString(6, home.worldName()); ps.setDouble(7, home.x()); ps.setDouble(8, home.y()); ps.setDouble(9, home.z());
        ps.setFloat(10, home.yaw()); ps.setFloat(11, home.pitch()); ps.setLong(12, home.createdAt()); ps.setLong(13, home.updatedAt()); ps.setLong(14, home.revision());
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        writer.shutdown(); queries.shutdown();
        try { writer.awaitTermination(5, TimeUnit.SECONDS); queries.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}
