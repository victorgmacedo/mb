package br.com.mb.engine.snapshot;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Typed SQL identifiers for the checkpoint schema, without reflective object mapping. */
final class SnapshotTables {
    static final Table<Record> CHECKPOINTS = DSL.table(DSL.name("book_snapshot_checkpoints"));
    static final Table<Record> BOOKS = DSL.table(DSL.name("book_snapshots"));
    static final Table<Record> OFFSETS = DSL.table(DSL.name("book_snapshot_offsets"));
    static final Field<UUID> ID = DSL.field(DSL.name("id"), UUID.class);
    static final Field<UUID> CHECKPOINT_ID = DSL.field(DSL.name("checkpoint_id"), UUID.class);
    static final Field<String> TOPIC = DSL.field(DSL.name("topic"), String.class);
    static final Field<OffsetDateTime> CREATED_AT = DSL.field(DSL.name("created_at"), OffsetDateTime.class);
    static final Field<Integer> BOOK_COUNT = DSL.field(DSL.name("book_count"), Integer.class);
    static final Field<Integer> PARTITION_COUNT = DSL.field(DSL.name("partition_count"), Integer.class);
    static final Field<String> INSTRUMENT = DSL.field(DSL.name("instrument"), String.class);
    static final Field<Integer> SCHEMA_VERSION = DSL.field(DSL.name("schema_version"), Integer.class);
    static final Field<byte[]> PAYLOAD = DSL.field(DSL.name("payload"), byte[].class);
    static final Field<String> CHECKSUM = DSL.field(DSL.name("checksum"), String.class);
    static final Field<Integer> PARTITION_ID = DSL.field(DSL.name("partition_id"), Integer.class);
    static final Field<Long> NEXT_OFFSET = DSL.field(DSL.name("next_offset"), Long.class);

    private SnapshotTables() {}
}
