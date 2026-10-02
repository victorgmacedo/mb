package br.com.mb.engine.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresBookSnapshotStoreTest {

    @Test
    void persistsBinarySnapshotsAndFallsBackAfterCorruption() throws Exception {
        var schema = "snapshot_test_" + UUID.randomUUID().toString().replace("-", "");
        var postgresUrl = System.getenv("MB_SNAPSHOT_TEST_JDBC_URL");
        var url = postgresUrl == null ? "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            : postgresUrl + (postgresUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var username = postgresUrl == null ? "sa" : "mb";
        var password = postgresUrl == null ? "" : "mb";
        try (var connection = DriverManager.getConnection(url, username, password)) {
            if (postgresUrl != null) {
                try (var statement = connection.createStatement()) { statement.executeUpdate("CREATE SCHEMA " + schema); }
            }
            try {
                var store = new PostgresBookSnapshotStore(url, username, password);
                store.initialize();
                assertTrue(store.loadLatestValid("book-journal").isEmpty());
                var book = new BookSnapshot("BTC/BRL", List.of(), List.of(
                    new BookSnapshotPriceLevel(100, List.of(new BookSnapshotOrder("seller", "sell", 6, 1, Instant.EPOCH)))), 3, 1);
                var older = new SnapshotCheckpoint(UUID.randomUUID(), "book-journal", Instant.EPOCH, Map.of(0, 3L), List.of(book));
                var newer = new SnapshotCheckpoint(UUID.randomUUID(), "book-journal", Instant.EPOCH.plusSeconds(1), Map.of(0, 4L), List.of(book));
                store.save(older);
                store.save(newer);
                assertEquals(newer, store.loadLatestValid("book-journal").orElseThrow());
                assertTrue(store.loadLatestValid("another-topic").isEmpty());
                try (var statement = connection.prepareStatement("UPDATE book_snapshots SET payload = ? WHERE checkpoint_id = ?")) {
                    statement.setBytes(1, new byte[] {1, 2, 3});
                    statement.setObject(2, newer.id());
                    statement.executeUpdate();
                }
                assertEquals(older, store.loadLatestValid("book-journal").orElseThrow());
                try (var statement = connection.prepareStatement("DELETE FROM book_snapshot_offsets WHERE checkpoint_id = ?")) {
                    statement.setObject(1, older.id());
                    statement.executeUpdate();
                }
                assertTrue(store.loadLatestValid("book-journal").isEmpty());
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE book_snapshot_offsets ADD CONSTRAINT reject_test_offset CHECK(next_offset < 5)");
                }
                var rejected = new SnapshotCheckpoint(UUID.randomUUID(), "book-journal", Instant.EPOCH.plusSeconds(2), Map.of(0, 5L), List.of(book));
                assertThrows(IllegalStateException.class, () -> store.save(rejected));
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM book_snapshot_checkpoints")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
            } finally {
                if (postgresUrl != null) {
                    try (var statement = connection.createStatement()) { statement.executeUpdate("DROP SCHEMA " + schema + " CASCADE"); }
                }
            }
        }
    }
}
