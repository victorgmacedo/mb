package br.com.mb.engine.snapshot;

import static br.com.mb.engine.snapshot.SnapshotTables.BOOKS;
import static br.com.mb.engine.snapshot.SnapshotTables.BOOK_COUNT;
import static br.com.mb.engine.snapshot.SnapshotTables.CHECKPOINTS;
import static br.com.mb.engine.snapshot.SnapshotTables.CHECKPOINT_ID;
import static br.com.mb.engine.snapshot.SnapshotTables.CHECKSUM;
import static br.com.mb.engine.snapshot.SnapshotTables.CREATED_AT;
import static br.com.mb.engine.snapshot.SnapshotTables.ID;
import static br.com.mb.engine.snapshot.SnapshotTables.INSTRUMENT;
import static br.com.mb.engine.snapshot.SnapshotTables.NEXT_OFFSET;
import static br.com.mb.engine.snapshot.SnapshotTables.OFFSETS;
import static br.com.mb.engine.snapshot.SnapshotTables.PARTITION_COUNT;
import static br.com.mb.engine.snapshot.SnapshotTables.PARTITION_ID;
import static br.com.mb.engine.snapshot.SnapshotTables.PAYLOAD;
import static br.com.mb.engine.snapshot.SnapshotTables.SCHEMA_VERSION;
import static br.com.mb.engine.snapshot.SnapshotTables.TOPIC;

import br.com.mb.ledger.jooq.JooqDatabase;
import br.com.mb.shared.logging.StructuredLogger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

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

    public static PostgresBookSnapshotStore fromEnvironment(Map<String, String> environment) {
        return new PostgresBookSnapshotStore(
            environment.getOrDefault("MB_LEDGER_JDBC_URL", "jdbc:postgresql://localhost:5432/mb"),
            environment.getOrDefault("MB_LEDGER_USERNAME", "mb"),
            environment.getOrDefault("MB_LEDGER_PASSWORD", "mb"));
    }

    public void initialize() {
        try (var connection = JooqDatabase.open(url, username, password)) {
            connection.execute("""
                CREATE TABLE IF NOT EXISTS book_snapshot_checkpoints (
                    id UUID PRIMARY KEY, topic VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, book_count INTEGER NOT NULL,
                    partition_count INTEGER NOT NULL)
                """);
            connection.execute("""
                CREATE INDEX IF NOT EXISTS book_snapshot_latest
                ON book_snapshot_checkpoints(topic, created_at DESC)
                """);
            connection.execute("""
                CREATE TABLE IF NOT EXISTS book_snapshots (
                    checkpoint_id UUID NOT NULL REFERENCES book_snapshot_checkpoints(id),
                    instrument VARCHAR(255) NOT NULL, schema_version INTEGER NOT NULL,
                    payload BYTEA NOT NULL, checksum VARCHAR(64) NOT NULL,
                    PRIMARY KEY(checkpoint_id, instrument))
                """);
            connection.execute("""
                CREATE TABLE IF NOT EXISTS book_snapshot_offsets (
                    checkpoint_id UUID NOT NULL REFERENCES book_snapshot_checkpoints(id),
                    partition_id INTEGER NOT NULL CHECK(partition_id >= 0),
                    next_offset BIGINT NOT NULL CHECK(next_offset >= 0),
                    PRIMARY KEY(checkpoint_id, partition_id))
                """);
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Could not initialize book snapshot storage", exception);
        }
    }

    @Override
    public Optional<SnapshotCheckpoint> loadLatestValid(String topic) {
        try (var connection = JooqDatabase.open(url, username, password)) {
            for (var row : connection.select(ID, CREATED_AT, BOOK_COUNT, PARTITION_COUNT).from(CHECKPOINTS)
                .where(TOPIC.eq(topic)).orderBy(CREATED_AT.desc(), ID.desc()).fetch()) {
                var id = row.get(ID);
                try {
                    var books = loadBooks(connection, id);
                    var offsets = loadOffsets(connection, id);
                    if (books.size() != row.get(BOOK_COUNT) || offsets.size() != row.get(PARTITION_COUNT)) {
                        throw new InvalidBookSnapshotException("Incomplete snapshot checkpoint");
                    }
                    var checkpoint = new SnapshotCheckpoint(id, topic, row.get(CREATED_AT).toInstant(), offsets, books);
                    new BookSnapshotRestorer().restore(books);
                    return Optional.of(checkpoint);
                } catch (InvalidBookSnapshotException | IllegalArgumentException exception) {
                    LOG.info("engine.snapshot.invalid", "checkpoint_id", id.toString(), "reason", exception.getMessage());
                }
            }
            return Optional.empty();
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Could not load book snapshot", exception);
        }
    }

    @Override
    public void save(SnapshotCheckpoint checkpoint) {
        new BookSnapshotRestorer().restore(checkpoint.books());
        try (var database = JooqDatabase.open(url, username, password)) {
            database.transaction(configuration -> {
                var context = configuration.dsl();
                context.insertInto(CHECKPOINTS, ID, TOPIC, CREATED_AT, BOOK_COUNT, PARTITION_COUNT)
                    .values(checkpoint.id(), checkpoint.topic(), checkpoint.createdAt().atOffset(ZoneOffset.UTC),
                        checkpoint.books().size(), checkpoint.nextOffsets().size()).execute();
                var books = checkpoint.books().stream().map(book -> {
                    var payload = codec.encode(book);
                    return new Object[] {checkpoint.id(), book.instrument(), book.schemaVersion(), payload, checksum(payload)};
                }).toArray(Object[][]::new);
                if (books.length > 0) {
                    context.batch(context.insertInto(BOOKS, CHECKPOINT_ID, INSTRUMENT, SCHEMA_VERSION, PAYLOAD, CHECKSUM)
                        .values((UUID) null, (String) null, (Integer) null, (byte[]) null, (String) null))
                        .bind(books).execute();
                }
                var offsets = checkpoint.nextOffsets().entrySet().stream().map(offset ->
                    new Object[] {checkpoint.id(), offset.getKey(), offset.getValue()}).toArray(Object[][]::new);
                if (offsets.length > 0) {
                    context.batch(context.insertInto(OFFSETS, CHECKPOINT_ID, PARTITION_ID, NEXT_OFFSET)
                        .values((UUID) null, (Integer) null, (Long) null)).bind(offsets).execute();
                }
            });
        } catch (DataAccessException exception) {
            throw new IllegalStateException("Could not save book snapshot", exception);
        }
    }

    private ArrayList<BookSnapshot> loadBooks(DSLContext context, UUID id) {
        var books = new ArrayList<BookSnapshot>();
        for (var row : context.select(INSTRUMENT, SCHEMA_VERSION, PAYLOAD, CHECKSUM).from(BOOKS)
            .where(CHECKPOINT_ID.eq(id)).orderBy(INSTRUMENT).fetch()) {
            var payload = row.get(PAYLOAD);
            if (!checksum(payload).equals(row.get(CHECKSUM))) {
                throw new InvalidBookSnapshotException("Snapshot checksum mismatch");
            }
            var book = codec.decode(payload);
            if (!book.instrument().equals(row.get(INSTRUMENT)) || book.schemaVersion() != row.get(SCHEMA_VERSION)) {
                throw new InvalidBookSnapshotException("Snapshot payload and metadata differ");
            }
            books.add(book);
        }
        return books;
    }

    private HashMap<Integer, Long> loadOffsets(DSLContext context, UUID id) {
        var offsets = new HashMap<Integer, Long>();
        for (var row : context.select(PARTITION_ID, NEXT_OFFSET).from(OFFSETS).where(CHECKPOINT_ID.eq(id)).fetch()) {
            offsets.put(row.get(PARTITION_ID), row.get(NEXT_OFFSET));
        }
        return offsets;
    }

    private static String checksum(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
