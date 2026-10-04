package br.com.mb.engine.durable;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.BookJournalReplayer;
import br.com.mb.engine.snapshot.BookSnapshotCodec;
import br.com.mb.engine.snapshot.BookSnapshotRestorer;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.jooq.JooqDatabase;
import br.com.mb.ledger.jooq.JooqLedger;
import br.com.mb.ledger.settlement.LedgerSettlementHandler;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.model.Asset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MB_LEDGER_TEST_JDBC_URL", matches = ".+")
class DurableEngineStoreTest {
    @Test
    void rollsBackReservationDecisionAndBookWhenOutboxInsertFails() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1000);
            fixture.ledger.atomic(db -> { db.execute("ALTER TABLE engine_outbox ADD CONSTRAINT inject_failure CHECK(topic <> 'book-journal')"); return null; });
            var order = order(0, 0, "buy", "buyer", "1", 100, 4);
            assertThrows(RuntimeException.class, () -> fixture.store.apply(order));
            assertEquals(new AssetBalance(1000, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertEquals(0, fixture.count("engine_commands"));
            assertEquals(0, fixture.count("engine_outbox"));
            assertTrue(fixture.store.bootstrap(() -> { throw new AssertionError("must not import again"); }).openOrders().isEmpty());
            fixture.ledger.atomic(db -> { db.execute("ALTER TABLE engine_outbox DROP CONSTRAINT inject_failure"); return null; });
            assertFalse(fixture.store.apply(order).duplicate());
            assertTrue(fixture.store.apply(order).duplicate());
            assertTrue(fixture.store.apply(order(0, 1, "buy", "buyer", "1", 100, 4)).duplicate());
            assertEquals(new AssetBalance(600, 400), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertEquals(2, fixture.count("engine_commands"));
            assertEquals(2, fixture.count("engine_outbox"));
        }
    }

    @Test
    void fencesPreviousOwnerAndRestoresBookBeforeMatchingAndCancellation() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ledger.credit(new AccountId("seller"), new Asset("BTC"), 10);
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1000);
            fixture.store.apply(order(0, 0, "sell", "seller", "2", 100, 10));
            var standby = fixture.newStore();
            standby.assigned(List.of(0));
            assertThrows(IllegalStateException.class, () -> fixture.store.apply(order(0, 1, "buy", "buyer", "1", 100, 4)));
            fixture.store.revoked(List.of(0)); // an old revoke must not clear the new owner's epoch
            var matched = standby.apply(order(0, 1, "buy", "buyer", "1", 100, 4));
            assertEquals(6, matched.state().openOrders().getFirst().remainingQuantity());
            var cancelled = standby.apply(message(0, 2, "8=FIX.4.4|35=F|1=seller|11=cancel|41=sell|55=BTC/BRL|"));
            assertTrue(cancelled.state().openOrders().isEmpty());
            assertEquals(new AssetBalance(6, 4), fixture.ledger.balanceOf(new AccountId("seller"), new Asset("BTC")));
            var outputs = new ArrayList<CommandMessage>();
            standby.publishPending(collector(outputs), 100);
            var settlement = outputs.stream().filter(output -> output.topic().equals("settlements")).findFirst().orElseThrow();
            var handler = new LedgerSettlementHandler(fixture.ledger, ignored -> {});
            handler.handle(settlement);
            handler.handle(settlement);
            assertEquals(new AssetBalance(6, 0), fixture.ledger.balanceOf(new AccountId("seller"), new Asset("BTC")));
            assertEquals(new AssetBalance(4, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BTC")));
            assertEquals("APPLIED", fixture.ledger.atomic(db -> db.fetchOne("SELECT status FROM settlement_instructions").get(0, String.class)));
        }
    }

    @Test
    void retriesPublishedOutboxWithStableIdsAndDeduplicatesJournalAcrossCheckpoints() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1000);
            fixture.store.apply(order(0, 0, "buy", "buyer", "1", 100, 4));
            var outputs = new ArrayList<CommandMessage>();
            assertThrows(IllegalStateException.class, () -> fixture.store.publishPending(new CommandPublisher() {
                public void publish(CommandMessage output) { outputs.add(output); throw new IllegalStateException("crash after Kafka ack"); }
                public void close() {}
            }, 100));
            fixture.store.publishPending(collector(outputs), 100);
            assertEquals(outputs.get(0), outputs.get(1));
            var journal = outputs.stream().filter(output -> output.topic().equals("book-journal")).toList();
            var replayer = new BookJournalReplayer(InstrumentCatalog.defaultCatalog());
            var state = replayer.replay(journal);
            assertEquals(1, state.openOrders().size());
            var codec = new BookSnapshotCodec();
            var checkpoint = state.books().stream().map(book -> codec.decode(codec.encode(book, state.lastEntrySequence()))).toList();
            var restored = new BookSnapshotRestorer().restore(checkpoint);
            replayer.replay(restored, journal);
            assertEquals(state.openOrders(), restored.openOrders());
            assertEquals(0, fixture.store.publishPending(collector(outputs), 100));
        }
    }

    @Test
    void persistsFailedSettlementAndRetriesWithoutAcknowledgingOrRepeatingEffects() throws Exception {
        try (var fixture = new Fixture()) {
            var instruction = new CommandMessage("settlements", "BRL", "8=FIX.4.4|35=U3|17=release|1=buyer|11=buy|55=BRL|38=50|");
            var handler = new LedgerSettlementHandler(fixture.ledger, ignored -> {});
            assertThrows(LedgerException.class, () -> handler.handle(instruction));
            assertEquals("FAILED", fixture.ledger.atomic(db -> db.fetchOne("SELECT status FROM settlement_instructions").get(0, String.class)));
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 50);
            fixture.ledger.reserve(new AccountId("buyer"), new Asset("BRL"), 50);
            handler.handle(instruction);
            handler.handle(instruction);
            assertEquals(new AssetBalance(50, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertEquals(Integer.valueOf(2), fixture.ledger.atomic(db -> db.fetchOne("SELECT attempts FROM settlement_instructions").get(0, Integer.class)));
        }
    }

    @Test
    void serializesDuplicateCommandsAcrossOwnersAndRejectsConflictingPayloads() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1000);
            var other = fixture.newStore();
            other.assigned(List.of(1));
            try (var workers = Executors.newFixedThreadPool(2)) {
                var results = workers.invokeAll(List.<Callable<DurableEngineStore.Applied>>of(
                    () -> fixture.store.apply(order(0, 0, "buy", "buyer", "1", 100, 4)),
                    () -> other.apply(order(1, 0, "buy", "buyer", "1", 100, 4))));
                assertNotEquals(results.get(0).get().duplicate(), results.get(1).get().duplicate());
            }
            var rejected = other.apply(order(1, 1, "buy", "buyer", "1", 100, 5));
            assertTrue(rejected.result().contains("REJECTED"));
            assertEquals(new AssetBalance(600, 400), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertThrows(IllegalStateException.class, () -> fixture.store.apply(order(0, 0, "buy", "buyer", "1", 100, 9)));
        }
    }

    @Test
    void rejectedFundingRollsBackItsLedgerDeduplicationRecord() throws Exception {
        try (var fixture = new Fixture()) {
            var result = fixture.store.apply(message(0, 0, "8=FIX.4.4|35=U6|1=buyer|11=debit|55=BRL|38=50|"));
            assertTrue(result.result().contains("REJECTED"));
            assertEquals(0, fixture.count("processed_commands"));
            assertEquals(1, fixture.count("engine_commands"));
            assertEquals(1, fixture.count("engine_outbox"));
        }
    }

    @Test
    void cancellationPreservesPendingTradeAndPriceImprovementReservations() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.ledger.credit(new AccountId("seller"), new Asset("BTC"), 4);
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1200);
            fixture.store.apply(order(0, 0, "sell", "seller", "2", 100, 4));
            fixture.store.apply(order(0, 1, "buy", "buyer", "1", 120, 10));
            fixture.store.apply(message(0, 2, "8=FIX.4.4|35=F|1=buyer|11=cancel|41=buy|55=BTC/BRL|"));
            assertEquals(new AssetBalance(720, 480), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            var handler = new LedgerSettlementHandler(fixture.ledger, ignored -> {});
            for (var payload : fixture.ledger.pendingInstructions(100)) {
                handler.handle(new CommandMessage("settlements", "BTC/BRL", payload));
            }
            assertTrue(fixture.ledger.pendingInstructions(100).isEmpty());
            assertEquals(new AssetBalance(800, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertEquals(new AssetBalance(4, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BTC")));
        }
    }

    @Test
    void quarantinesMalformedSettlementBeforeAcknowledgingIt() throws Exception {
        try (var fixture = new Fixture()) {
            var invalid = new CommandMessage("settlements", "BTC/BRL", "invalid", 0, 12);
            var handler = new LedgerSettlementHandler(fixture.ledger, ignored -> {});
            handler.handle(invalid);
            handler.handle(invalid);
            assertEquals(1, fixture.count("invalid_settlements"));
            assertEquals(0, fixture.count("processed_commands"));
        }
    }

    @Test
    void importsLegacyIdsIncludingFilledOrdersToPreventRepeatedReservations() throws Exception {
        try (var fixture = new Fixture(false)) {
            var journal = List.of(
                new CommandMessage("book-journal", "BTC/BRL", "8=FIX.4.4|35=U4|1=seller|11=sell|55=BTC/BRL|54=2|44=100|38=4|10003=1|10004=2026-10-02T12:00:00Z|"),
                new CommandMessage("book-journal", "BTC/BRL", "8=FIX.4.4|35=U4|1=buyer|11=buy|55=BTC/BRL|54=1|44=100|38=4|10003=2|10004=2026-10-02T12:00:01Z|"));
            var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(journal);
            fixture.store.bootstrapWithHistory(() -> new DurableEngineStore.ImportedState(state, journal));
            fixture.ledger.credit(new AccountId("buyer"), new Asset("BRL"), 1000);
            assertTrue(fixture.store.apply(order(0, 0, "buy", "buyer", "1", 100, 4)).result().contains("legacy order already accepted"));
            assertEquals(new AssetBalance(1000, 0), fixture.ledger.balanceOf(new AccountId("buyer"), new Asset("BRL")));
            assertTrue(fixture.store.bootstrap(() -> { throw new AssertionError("must import only once"); }).openOrders().isEmpty());
        }
    }

    private static CommandMessage order(int partition, long offset, String id, String account, String side, long price, long quantity) {
        return message(partition, offset, "8=FIX.4.4|35=D|1=%s|11=%s|55=BTC/BRL|54=%s|44=%d|38=%d|".formatted(account, id, side, price, quantity));
    }

    private static CommandMessage message(int partition, long offset, String fix) {
        return new CommandMessage("commands", FixMessage.parse(fix).kafkaKey(), fix, partition, offset);
    }

    private static CommandPublisher collector(List<CommandMessage> outputs) {
        return new CommandPublisher() {
            public void publish(CommandMessage output) { outputs.add(output); }
            public void close() {}
        };
    }

    private static final class Fixture implements AutoCloseable {
        private final String baseUrl = System.getenv("MB_LEDGER_TEST_JDBC_URL");
        private final String user = System.getenv().getOrDefault("MB_LEDGER_USERNAME", "mb");
        private final String password = System.getenv().getOrDefault("MB_LEDGER_PASSWORD", "mb");
        private final String schema = "engine_test_" + UUID.randomUUID().toString().replace("-", "");
        private final JooqLedger ledger;
        private final DurableEngineStore store;

        private Fixture() { this(true); }

        private Fixture(boolean bootstrap) {
            try (var db = JooqDatabase.open(baseUrl, user, password)) { db.execute("CREATE SCHEMA " + schema); }
            ledger = new JooqLedger(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            ledger.initialize();
            store = newStore();
            store.initialize();
            if (bootstrap) store.bootstrap(EngineState::new);
            store.assigned(List.of(0));
        }

        private DurableEngineStore newStore() {
            return new DurableEngineStore(ledger, "commands", "events", "book-journal", "settlements");
        }

        private int count(String table) {
            return ledger.atomic(db -> db.fetchOne("SELECT count(*) FROM " + table).get(0, Integer.class));
        }

        public void close() {
            try (var db = JooqDatabase.open(baseUrl, user, password)) { db.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
