package com.plexon.homes.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeRepositoryFutureSchemaTest {
    @TempDir Path temp;

    @Test void schemaThreeIsRejectedWithoutLogicalDatabaseMutation() throws Exception {
        Path db = temp.resolve("future-schema.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            statement.execute("INSERT INTO migration_meta VALUES('schema_version','3')");
            statement.execute("CREATE TABLE future_payload(id INTEGER PRIMARY KEY,value TEXT NOT NULL)");
            statement.execute("INSERT INTO future_payload(value) VALUES('must-survive')");
        }
        LogicalState before = state(db);

        try (var repository = new HomeRepository(db)) {
            assertThrows(CompletionException.class, () -> repository.ready().join());
        }

        LogicalState after = state(db);
        assertEquals(before, after);
        assertEquals("3", after.marker());
        assertEquals("must-survive", after.sentinel());
        assertFalse(after.schema().stream().anyMatch(line -> line.contains("|players|")));
        assertFalse(after.schema().stream().anyMatch(line -> line.contains("|homes|")));
    }

    private static LogicalState state(Path db) throws Exception {
        List<String> schema = new ArrayList<>();
        String marker;
        String sentinel;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("SELECT type,name,COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")) {
                while (rs.next()) schema.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
            try (var rs = statement.executeQuery("SELECT value FROM migration_meta WHERE key='schema_version'")) {
                rs.next(); marker = rs.getString(1);
            }
            try (var rs = statement.executeQuery("SELECT value FROM future_payload WHERE id=1")) {
                rs.next(); sentinel = rs.getString(1);
            }
        }
        return new LogicalState(List.copyOf(schema), marker, sentinel);
    }

    private record LogicalState(List<String> schema, String marker, String sentinel) { }
}
