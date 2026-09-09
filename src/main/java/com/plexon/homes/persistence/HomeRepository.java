package com.plexon.homes.persistence;

import com.plexon.homes.model.Home;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class HomeRepository implements AutoCloseable {
    private final Path database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("PlexonHomes-SQLite-Writer").factory());
    private final ExecutorService queries = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("PlexonHomes-SQLite-Query-", 0).factory());
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final CompletableFuture<Void> ready;

    public HomeRepository(Path database) {
        this.database = database;
        this.ready = CompletableFuture.runAsync(this::initializeDatabase, writer);
    }

    public CompletableFuture<Void> ready() { return ready; }
    public long writes() { return writes.get(); }
    public long failures() { return failures.get(); }

    public CompletableFuture<List<Home>> loadHomes(UUID owner) {
        return ready.thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
            List<Home> homes = new ArrayList<>();
            try (Connection connection = open();
                 PreparedStatement ps = connection.prepareStatement("SELECT owner_uuid,home_id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision FROM homes WHERE owner_uuid=? ORDER BY created_at ASC")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) homes.add(read(rs));
                }
                return List.copyOf(homes);
            } catch (Exception exception) {
                failures.incrementAndGet();
                throw new RuntimeException("Unable to load homes for " + owner, exception);
            }
        }, queries));
    }

    public CompletableFuture<Void> upsert(Home home) {
        return write(() -> {
            try (Connection connection = open();
                 PreparedStatement ps = connection.prepareStatement("INSERT INTO homes(owner_uuid,home_id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_uuid,home_id) DO UPDATE SET display_name=excluded.display_name,world_uuid=excluded.world_uuid,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,updated_at=excluded.updated_at,revision=excluded.revision")) {
                bind(ps, home);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> delete(UUID owner, String id) {
        return write(() -> {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("DELETE FROM homes WHERE owner_uuid=? AND home_id=?")) {
                ps.setString(1, owner.toString()); ps.setString(2, id); ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> rename(Home previous, Home renamed) {
        return write(() -> {
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement del = connection.prepareStatement("DELETE FROM homes WHERE owner_uuid=? AND home_id=?");
                     PreparedStatement ins = connection.prepareStatement("INSERT INTO homes(owner_uuid,home_id,display_name,world_uuid,world_name,x,y,z,yaw,pitch,created_at,updated_at,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    del.setString(1, previous.ownerId().toString()); del.setString(2, previous.id()); del.executeUpdate();
                    bind(ins, renamed); ins.executeUpdate(); connection.commit();
                } catch (Exception exception) {
                    connection.rollback(); throw exception;
                } finally { connection.setAutoCommit(true); }
            }
        });
    }

    public CompletableFuture<Void> rememberPlayer(UUID uuid, String name) {
        return write(() -> {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement("INSERT INTO players(player_uuid,last_name,updated_at) VALUES(?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name,updated_at=excluded.updated_at")) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Path> backup(Path directory) {
        return ready.thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                try (Connection connection = open(); Statement statement = connection.createStatement()) { statement.execute("PRAGMA wal_checkpoint(FULL)"); }
                Path target = directory.resolve("homes-" + Instant.now().toEpochMilli() + ".db");
                Files.copy(database, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (Exception exception) {
                failures.incrementAndGet(); throw new RuntimeException(exception);
            }
        }, writer));
    }

    private CompletableFuture<Void> write(SqlAction action) {
        return ready.thenCompose(ignored -> CompletableFuture.runAsync(() -> {
            try { action.run(); writes.incrementAndGet(); }
            catch (Exception exception) { failures.incrementAndGet(); throw new RuntimeException(exception); }
        }, writer));
    }

    private void initializeDatabase() {
        try {
            Files.createDirectories(database.getParent());
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS homes(owner_uuid TEXT NOT NULL,home_id TEXT NOT NULL,display_name TEXT NOT NULL,world_uuid TEXT NOT NULL,world_name TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(owner_uuid,home_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS players(player_uuid TEXT PRIMARY KEY,last_name TEXT,updated_at INTEGER)");
                statement.execute("CREATE TABLE IF NOT EXISTS migration_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            }
        } catch (Exception exception) {
            failures.incrementAndGet(); throw new RuntimeException("Unable to initialize homes database", exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static Home read(ResultSet rs) throws SQLException {
        return new Home(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), UUID.fromString(rs.getString(4)), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getFloat(9), rs.getFloat(10), rs.getLong(11), rs.getLong(12), rs.getLong(13));
    }

    private static void bind(PreparedStatement ps, Home home) throws SQLException {
        ps.setString(1, home.ownerId().toString()); ps.setString(2, home.id()); ps.setString(3, home.displayName()); ps.setString(4, home.worldId().toString()); ps.setString(5, home.worldName()); ps.setDouble(6, home.x()); ps.setDouble(7, home.y()); ps.setDouble(8, home.z()); ps.setFloat(9, home.yaw()); ps.setFloat(10, home.pitch()); ps.setLong(11, home.createdAt()); ps.setLong(12, home.updatedAt()); ps.setLong(13, home.revision());
    }

    @Override public void close() {
        writer.shutdown(); queries.shutdown();
        try { writer.awaitTermination(5, TimeUnit.SECONDS); queries.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
}
