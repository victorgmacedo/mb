package br.com.mb.engine.durable;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.command.EngineCommandResult;
import br.com.mb.engine.command.EngineEventFactory;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.KafkaBookJournal;
import br.com.mb.engine.journal.KafkaSettlementJournal;
import br.com.mb.engine.snapshot.BookSnapshotCodec;
import br.com.mb.engine.snapshot.BookSnapshotRestorer;
import br.com.mb.ledger.jooq.JooqLedger;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;

/** PostgreSQL is authoritative. Kafka outputs are projections delivered at least once. */
public final class DurableEngineStore {
    private final JooqLedger ledger;
    private final String commandsTopic;
    private final String eventsTopic;
    private final String journalTopic;
    private final String settlementsTopic;
    private final String owner = UUID.randomUUID().toString();
    private final Map<Integer, Long> epochs = new HashMap<>();
    private final BookSnapshotCodec codec = new BookSnapshotCodec();

    public DurableEngineStore(JooqLedger ledger, String commandsTopic, String eventsTopic,
                              String journalTopic, String settlementsTopic) {
        this.ledger = Objects.requireNonNull(ledger);
        this.commandsTopic = commandsTopic;
        this.eventsTopic = eventsTopic;
        this.journalTopic = journalTopic;
        this.settlementsTopic = settlementsTopic;
    }

    public void initialize() {
        ledger.atomic(db -> {
            db.execute("SELECT pg_advisory_xact_lock(724813522)");
            try (var input = DurableEngineStore.class.getResourceAsStream("/engine-durable-schema.sql")) {
                if (input == null) throw new IllegalStateException("engine durable schema missing");
                for (var sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                    if (!sql.isBlank()) db.execute(sql);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read engine durable schema", exception);
            }
            db.execute("INSERT INTO engine_state_guard(id,initialized,last_sequence) VALUES(1,false,0) ON CONFLICT(id) DO NOTHING");
            return null;
        });
    }

    /** Only the first initializer imports legacy state. All legacy writers must already be stopped. */
    public record ImportedState(EngineState state, List<CommandMessage> journal) {
        public ImportedState { journal = List.copyOf(journal); }
    }

    public EngineState bootstrap(Supplier<EngineState> legacyState) {
        return bootstrapWithHistory(() -> new ImportedState(legacyState.get(), List.of()));
    }

    public EngineState bootstrapWithHistory(Supplier<ImportedState> legacyState) {
        return ledger.atomic(db -> {
            lock(db);
            if (!db.fetchOne("SELECT initialized FROM engine_state_guard WHERE id=1").get(0, Boolean.class)) {
                var imported = legacyState.get();
                saveState(db, imported.state());
                for (var entry : imported.journal()) {
                    var fix = FixMessage.parse(entry.value());
                    if (fix.field(35).orElseThrow().equals("U4")) {
                        db.execute("INSERT INTO engine_legacy_order_ids(client_order_id) VALUES(?) ON CONFLICT(client_order_id) DO NOTHING",
                            fix.field(11).orElseThrow());
                    }
                }
                db.execute("UPDATE engine_state_guard SET initialized=true WHERE id=1");
            }
            return loadState(db);
        });
    }

    public void assigned(List<Integer> partitions) {
        // The guard also serializes handover against in-flight financial transactions.
        var acquired = ledger.atomic(db -> {
            lock(db);
            var result = new HashMap<Integer, Long>();
            for (var partition : partitions) {
                var row = db.fetchOne("INSERT INTO engine_partition_ownership(topic,partition_id,owner,epoch) VALUES(?,?,?,1) "
                    + "ON CONFLICT(topic,partition_id) DO UPDATE SET owner=EXCLUDED.owner,epoch=engine_partition_ownership.epoch+1 RETURNING epoch",
                    commandsTopic, partition, owner);
                result.put(partition, row.get(0, Long.class));
            }
            return result;
        });
        epochs.putAll(acquired);
    }

    public void revoked(List<Integer> partitions) {
        ledger.atomic(db -> {
            lock(db);
            for (var partition : partitions) {
                db.execute("UPDATE engine_partition_ownership SET owner=NULL WHERE topic=? AND partition_id=? AND owner=? AND epoch=?",
                    commandsTopic, partition, owner, epochs.getOrDefault(partition, -1L));
            }
            return null;
        });
        partitions.forEach(epochs::remove);
    }

    public record Applied(EngineState state, String result, boolean duplicate) {}

    public Applied apply(CommandMessage message) {
        validateSource(message);
        return ledger.atomic(db -> applyInTransaction(db, message));
    }

    private Applied applyInTransaction(DSLContext db, CommandMessage message) {
        lock(db);
        requireBootstrapped(db);
        fence(db, message.partition());

        var previous = findBySource(db, message);
        if (previous != null) return recoverSourceDuplicate(db, message, previous);

        var businessId = businessId(message);
        var business = findByBusinessId(db, businessId);
        if (business != null && business.matches(message)) {
            recordDuplicate(db, message, business);
            return new Applied(loadState(db), business.result(), true);
        }

        var execution = executeCommand(db, message, businessId, business);
        var decision = UUID.randomUUID().toString();
        recordDecision(db, message, decision, business == null ? businessId : null, execution.result());
        persistOutputs(db, decision, execution);
        if (execution.result().accepted()) saveState(db, execution.state());
        return new Applied(execution.state(), execution.result().line(), false);
    }

    private void validateSource(CommandMessage message) {
        if (!commandsTopic.equals(message.topic()) || message.partition() < 0 || message.offset() < 0) {
            throw new IllegalArgumentException("Durable commands require source topic, partition and offset");
        }
    }

    private void requireBootstrapped(DSLContext db) {
        if (!db.fetchOne("SELECT initialized FROM engine_state_guard WHERE id=1").get(0, Boolean.class)) {
            throw new IllegalStateException("Durable engine must be bootstrapped before processing commands");
        }
    }

    private record StoredCommand(String id, String payload, String result, String key) {
        boolean matches(CommandMessage message) {
            return payload.equals(message.value()) && key.equals(message.key());
        }
    }

    private StoredCommand findBySource(DSLContext db, CommandMessage message) {
        var row = db.fetchOne("SELECT id,payload,result,message_key FROM engine_commands WHERE topic=? AND partition_id=? AND source_offset=?",
            message.topic(), message.partition(), message.offset());
        return row == null ? null : new StoredCommand(row.get(0, String.class), row.get(1, String.class),
            row.get(2, String.class), row.get(3, String.class));
    }

    private StoredCommand findByBusinessId(DSLContext db, String businessId) {
        if (businessId == null) return null;
        var row = db.fetchOne("SELECT id,payload,result,message_key FROM engine_commands WHERE business_id=?", businessId);
        return row == null ? null : new StoredCommand(row.get(0, String.class), row.get(1, String.class),
            row.get(2, String.class), row.get(3, String.class));
    }

    private Applied recoverSourceDuplicate(DSLContext db, CommandMessage message, StoredCommand previous) {
        if (!previous.matches(message)) throw new IllegalStateException("Source offset payload changed");
        return new Applied(loadState(db), previous.result(), true);
    }

    private void recordDuplicate(DSLContext db, CommandMessage message, StoredCommand original) {
        db.execute("INSERT INTO engine_commands(id,topic,partition_id,source_offset,message_key,payload,status,result,owner_epoch,duplicate_of) VALUES(?,?,?,?,?,?,'DUPLICATE',?,?,?)",
            UUID.randomUUID().toString(), message.topic(), message.partition(), message.offset(), message.key(), message.value(),
            original.result(), epochs.get(message.partition()), original.id());
    }

    private record CommandExecution(EngineState state, EngineCommandResult result, List<CommandMessage> outputs) {}

    private CommandExecution executeCommand(DSLContext db, CommandMessage message, String businessId, StoredCommand business) {
        // A local view may lag other owners. Calculate against a fresh private copy under the global guard.
        var state = loadState(db);
        var outputs = new ArrayList<CommandMessage>();
        var handler = createCommandHandler(db, state, outputs);
        db.execute("SAVEPOINT engine_command_effects");
        var rejection = rejectionReason(db, message, state, businessId, business);
        var result = rejection != null
            ? EngineCommandResult.rejected(message.key(), rejection, new EngineEventFactory().rejected(message.key(), rejection))
            : handler.classify(message);
        if (!result.accepted()) {
            db.execute("ROLLBACK TO SAVEPOINT engine_command_effects");
            // SQL rollback cannot undo mutations to Java objects or collected messages.
            state = loadState(db);
            outputs.clear();
        }
        db.execute("RELEASE SAVEPOINT engine_command_effects");
        for (var event : result.eventFixMessages()) outputs.add(new CommandMessage(eventsTopic, message.key(), event));
        return new CommandExecution(state, result, outputs);
    }

    private EngineCommandHandler createCommandHandler(DSLContext db, EngineState state, List<CommandMessage> outputs) {
        var collector = new CommandPublisher() {
            public void publish(CommandMessage output) { outputs.add(output); }
            public void close() {}
        };
        return new EngineCommandHandler(collector, eventsTopic, ignored -> {}, JooqLedger.participatingIn(db),
            new KafkaSettlementJournal(collector, settlementsTopic), new KafkaBookJournal(collector, journalTopic), state);
    }

    private String rejectionReason(DSLContext db, CommandMessage message, EngineState state,
                                   String businessId, StoredCommand business) {
        var routingError = routingError(message, state);
        var legacyDuplicate = businessId != null && businessId.startsWith("D:")
            && db.fetchOne("SELECT client_order_id FROM engine_legacy_order_ids WHERE client_order_id=?", businessId.substring(2)) != null;
        if (business != null) return "conflicting duplicate command";
        if (legacyDuplicate) return "legacy order already accepted; reconcile its original result";
        return routingError;
    }

    private void recordDecision(DSLContext db, CommandMessage message, String decision,
                                String businessId, EngineCommandResult result) {
        db.execute("INSERT INTO engine_commands(id,topic,partition_id,source_offset,message_key,business_id,payload,status,result,owner_epoch) VALUES(?,?,?,?,?,?,?,?,?,?)",
            decision, message.topic(), message.partition(), message.offset(), message.key(), businessId,
            message.value(), result.accepted() ? "APPLIED" : "REJECTED", result.line(), epochs.get(message.partition()));
    }

    private void persistOutputs(DSLContext db, String decision, CommandExecution execution) {
        var index = 0;
        for (var output : execution.outputs()) {
            var outputId = decision + ":" + index++;
            var value = prepareOutput(output, outputId, execution.state());
            db.execute("INSERT INTO engine_outbox(message_id,topic,message_key,payload) VALUES(?,?,?,?)",
                outputId, output.topic(), output.key(), value);
            if (output.topic().equals(settlementsTopic)) recordSettlementInstruction(db, value);
        }
    }

    private String prepareOutput(CommandMessage output, String outputId, EngineState state) {
        var value = output.value();
        if (output.topic().equals(journalTopic)) value = sequenceJournalMutation(value, state);
        return value + "10005=" + outputId + FixMessage.SOH;
    }

    private String sequenceJournalMutation(String value, EngineState state) {
        // Cancel mutations also consume a sequence, so snapshots can discard repeated delivery.
        if (FixMessage.parse(value).field(35).orElseThrow().equals("U5")) {
            value += "10003=" + state.nextEntrySequence() + FixMessage.SOH;
        }
        var fix = FixMessage.parse(value);
        var instrument = InstrumentCatalog.defaultCatalog().findBySymbol(fix.field(55).orElseThrow()).orElseThrow();
        state.book(instrument).advanceJournalSequence(Long.parseLong(fix.field(10003).orElseThrow()));
        return value;
    }

    private void recordSettlementInstruction(DSLContext db, String value) {
        var executionId = FixMessage.parse(value).field(17).orElseThrow();
        db.execute("INSERT INTO settlement_instructions(execution_id,payload,status) VALUES(?,?,'PENDING') ON CONFLICT(execution_id) DO NOTHING",
            executionId, value);
    }

    /** One global publisher lock preserves decision order, including during publisher failover. */
    public int publishPending(CommandPublisher publisher, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return ledger.atomic(db -> {
            db.execute("SELECT pg_advisory_xact_lock(724813523)");
            var rows = db.fetch("SELECT id,topic,message_key,payload FROM engine_outbox WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE", limit);
            for (var row : rows) {
                publisher.publish(new CommandMessage(row.get(1, String.class), row.get(2, String.class), row.get(3, String.class)));
                db.execute("UPDATE engine_outbox SET published_at=CURRENT_TIMESTAMP WHERE id=?", row.get(0, Long.class));
            }
            return rows.size();
        });
    }

    private void lock(DSLContext db) {
        if (db.fetchOne("SELECT id FROM engine_state_guard WHERE id=1 FOR UPDATE") == null) {
            throw new IllegalStateException("Durable engine schema is not initialized");
        }
    }

    private void fence(DSLContext db, int partition) {
        var epoch = epochs.get(partition);
        var row = db.fetchOne("SELECT owner,epoch FROM engine_partition_ownership WHERE topic=? AND partition_id=?", commandsTopic, partition);
        if (epoch == null || row == null || !owner.equals(row.get(0, String.class)) || !epoch.equals(row.get(1, Long.class))) {
            throw new IllegalStateException("Engine fenced for partition " + partition);
        }
    }

    private EngineState loadState(DSLContext db) {
        var snapshots = db.fetch("SELECT payload FROM engine_durable_books ORDER BY instrument").stream()
            .map(row -> codec.decode(row.get(0, byte[].class))).toList();
        var state = new BookSnapshotRestorer().restore(snapshots);
        state.restoreEntrySequence(db.fetchOne("SELECT last_sequence FROM engine_state_guard WHERE id=1").get(0, Long.class));
        return state;
    }

    private void saveState(DSLContext db, EngineState state) {
        for (var book : state.books()) {
            db.execute("INSERT INTO engine_durable_books(instrument,payload) VALUES(?,?) ON CONFLICT(instrument) DO UPDATE SET payload=EXCLUDED.payload",
                book.instrument().symbol(), codec.encode(book, state.lastEntrySequence()));
        }
        db.execute("UPDATE engine_state_guard SET last_sequence=? WHERE id=1", state.lastEntrySequence());
    }

    private String routingError(CommandMessage message, EngineState state) {
        try {
            var fix = FixMessage.parse(message.value());
            if (!fix.field(55).map(message.key()::equals).orElse(false)) return "command key must match Symbol/Asset(55)";
            if (fix.field(35).orElseThrow().equals("F")) {
                var originalId = fix.field(41).orElse("");
                if (!originalId.isBlank()) {
                    var order = state.openOrder(new ClientOrderId(originalId));
                    if (order.isPresent() && !order.orElseThrow().instrument().symbol().equals(message.key())) {
                        return "cancel instrument differs from original order";
                    }
                }
            }
            return null;
        } catch (InvalidFixMessageException exception) {
            return null; // The command classifier persists the protocol rejection.
        }
    }

    private String businessId(CommandMessage message) {
        try {
            var fix = FixMessage.parse(message.value());
            // Current engine and settlement IDs are globally scoped by ClOrdID.
            return fix.field(11).map(id -> fix.field(35).orElseThrow() + ":" + id).orElse(null);
        } catch (InvalidFixMessageException exception) {
            return null;
        }
    }
}
