package br.com.mb.engine.snapshot;

import br.com.mb.shared.logging.StructuredLogger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PostgresBookSnapshotStore implements BookSnapshotStore {

    private static final StructuredLogger LOG = StructuredLogger.forClass(PostgresBookSnapshotStore.class);
    private final String url;
    private final String username;
    private final String password;
    private final BookSnapshotCodec codec = new BookSnapshotCodec();

    public PostgresBookSnapshotStore(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public static PostgresBookSnapshotStore fromEnvironment(java.util.Map<String, String> environment) {
        return new PostgresBookSnapshotStore(
            environment.getOrDefault("MB_LEDGER_JDBC_URL", "jdbc:postgresql://localhost:5432/mb"),
            environment.getOrDefault("MB_LEDGER_USERNAME", "mb"),
            environment.getOrDefault("MB_LEDGER_PASSWORD", "mb"));
    }

    public void initialize() {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS book_snapshot_checkpoints (
                    id UUID PRIMARY KEY, topic VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, book_count INTEGER NOT NULL,
                    partition_count INTEGER NOT NULL)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS book_snapshot_latest
                ON book_snapshot_checkpoints(topic, created_at DESC)
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS book_snapshots (
                    checkpoint_id UUID NOT NULL REFERENCES book_snapshot_checkpoints(id),
                    instrument VARCHAR(255) NOT NULL, schema_version INTEGER NOT NULL,
                    payload BYTEA NOT NULL, checksum VARCHAR(64) NOT NULL,
                    PRIMARY KEY(checkpoint_id, instrument))
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS book_snapshot_offsets (
                    checkpoint_id UUID NOT NULL REFERENCES book_snapshot_checkpoints(id),
                    partition_id INTEGER NOT NULL CHECK(partition_id >= 0),
                    next_offset BIGINT NOT NULL CHECK(next_offset >= 0),
                    PRIMARY KEY(checkpoint_id, partition_id))
                """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize book snapshot storage", exception);
        }
    }

    @Override
    public Optional<SnapshotCheckpoint> loadLatestValid(String topic) {
        try (var connection = connect(); var query = connection.prepareStatement("""
            SELECT id, created_at, book_count, partition_count FROM book_snapshot_checkpoints
            WHERE topic = ? ORDER BY created_at DESC, id DESC
            """)) {
            query.setString(1, topic);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var id = rows.getObject("id", UUID.class);
                    try {
                        var books = loadBooks(connection, id);
                        var offsets = loadOffsets(connection, id);
                        if (books.size() != rows.getInt("book_count") || offsets.size() != rows.getInt("partition_count")) {
                            throw new InvalidBookSnapshotException("Incomplete snapshot checkpoint");
                        }
                        var checkpoint = new SnapshotCheckpoint(id, topic, rows.getTimestamp("created_at").toInstant(), offsets, books);
                        new BookSnapshotRestorer().restore(books);
                        return Optional.of(checkpoint);
                    } catch (InvalidBookSnapshotException | IllegalArgumentException exception) {
                        LOG.info("engine.snapshot.invalid", "checkpoint_id", id.toString(), "reason", exception.getMessage());
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load book snapshot", exception);
        }
    }

    @Override
    public void save(SnapshotCheckpoint checkpoint) {
        new BookSnapshotRestorer().restore(checkpoint.books());
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement("INSERT INTO book_snapshot_checkpoints VALUES (?, ?, ?, ?, ?)")) {
                    insert.setObject(1, checkpoint.id());
                    insert.setString(2, checkpoint.topic());
                    insert.setTimestamp(3, Timestamp.from(checkpoint.createdAt()));
                    insert.setInt(4, checkpoint.books().size());
                    insert.setInt(5, checkpoint.nextOffsets().size());
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO book_snapshots VALUES (?, ?, ?, ?, ?)")) {
                    for (var book : checkpoint.books()) {
                        insert.setObject(1, checkpoint.id());
                        insert.setString(2, book.instrument());
                        insert.setInt(3, book.schemaVersion());
                        var payload = codec.encode(book);
                        insert.setBytes(4, payload);
                        insert.setString(5, checksum(payload));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (var insert = connection.prepareStatement("INSERT INTO book_snapshot_offsets VALUES (?, ?, ?)")) {
                    for (var offset : checkpoint.nextOffsets().entrySet()) {
                        insert.setObject(1, checkpoint.id());
                        insert.setInt(2, offset.getKey());
                        insert.setLong(3, offset.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save book snapshot", exception);
        }
    }

    private ArrayList<BookSnapshot> loadBooks(Connection connection, UUID id) throws SQLException {
        var books = new ArrayList<BookSnapshot>();
        try (var query = connection.prepareStatement("SELECT instrument, schema_version, payload, checksum FROM book_snapshots WHERE checkpoint_id = ? ORDER BY instrument")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var payload = rows.getBytes("payload");
                    if (!checksum(payload).equals(rows.getString("checksum"))) {
                        throw new InvalidBookSnapshotException("Snapshot checksum mismatch");
                    }
                    var book = codec.decode(payload);
                    if (!book.instrument().equals(rows.getString("instrument")) || book.schemaVersion() != rows.getInt("schema_version")) {
                        throw new InvalidBookSnapshotException("Snapshot payload and metadata differ");
                    }
                    books.add(book);
                }
            }
        }
        return books;
    }

    private HashMap<Integer, Long> loadOffsets(Connection connection, UUID id) throws SQLException {
        var offsets = new HashMap<Integer, Long>();
        try (var query = connection.prepareStatement("SELECT partition_id, next_offset FROM book_snapshot_offsets WHERE checkpoint_id = ?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    offsets.put(rows.getInt("partition_id"), rows.getLong("next_offset"));
                }
            }
        }
        return offsets;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static String checksum(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
