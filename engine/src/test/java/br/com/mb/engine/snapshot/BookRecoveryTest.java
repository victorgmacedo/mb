package br.com.mb.engine.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer.ReplayResult;
import br.com.mb.engine.command.CancelOrderCommand;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.BookJournalReplayer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookRecoveryTest {

    @Test
    void resumesReplayAndPreservesPartialQuantitiesPriorityAndGlobalSequence() {
        var prefix = List.of(accepted("seller", "sell-1", "2", 100, 10, 1),
            accepted("seller", "sell-2", "2", 100, 7, 2),
            accepted("buyer", "buy-1", "1", 100, 4, 3));
        var tail = List.of(accepted("buyer", "buy-2", "1", 100, 8, 4));
        var full = new ArrayList<>(prefix);
        full.addAll(tail);
        var expected = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(full);
        var prefixState = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(prefix);
        var codec = new BookSnapshotCodec();
        var checkpoint = new SnapshotCheckpoint(UUID.randomUUID(), "book-journal", Instant.now(), Map.of(0, 3L),
            prefixState.books().stream().map(book -> codec.decode(codec.encode(book, 3))).toList());
        var store = new MemoryStore(checkpoint);
        var result = new BookRecovery(store).recover("book-journal", offsets -> {
            assertEquals(Map.of(0, 3L), offsets);
            return new ReplayResult(tail, Map.of(0, 4L));
        });
        assertTrue(result.snapshotRestored());
        assertEquals(1, result.replayedMessages());
        assertEquals(expected.openOrders(), result.state().openOrders());
        assertEquals(5, result.state().nextEntrySequence());
        assertEquals(Map.of(0, 4L), store.checkpoint.nextOffsets());
        var cancelled = result.state().cancel(new CancelOrderCommand("seller", "cancel", "sell-2"));
        assertEquals(5, cancelled.remainingQuantity());
        assertFalse(result.state().hasOpenOrder(new ClientOrderId("sell-2")));
    }

    @Test
    void restoresSequenceEvenWhenAllOrdersHaveFilled() {
        var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(List.of(
            accepted("seller", "sell", "2", 100, 1, 1), accepted("buyer", "buy", "1", 100, 1, 2)));
        var snapshot = new BookSnapshotCodec().snapshotFrom(state.books().getFirst(), state.lastEntrySequence());
        var restored = new BookSnapshotRestorer().restore(List.of(snapshot));
        assertTrue(restored.openOrders().isEmpty());
        assertEquals(3, restored.nextEntrySequence());
    }

    @Test
    void rejectsUnsupportedSchemaAndCrossedBook() {
        assertThrows(InvalidBookSnapshotException.class, () -> new BookSnapshotRestorer().restore(List.of(
            new BookSnapshot("BTC/BRL", List.of(), List.of(), 0, 99))));
        var bid = new BookSnapshotPriceLevel(100, List.of(new BookSnapshotOrder("buyer", "buy", 1, 1, Instant.EPOCH)));
        var ask = new BookSnapshotPriceLevel(100, List.of(new BookSnapshotOrder("seller", "sell", 1, 2, Instant.EPOCH)));
        assertThrows(InvalidBookSnapshotException.class, () -> new BookSnapshotRestorer().restore(List.of(
            new BookSnapshot("BTC/BRL", List.of(bid), List.of(ask), 2, 1))));
    }

    @Test
    void fallsBackToFullReplayWhenNoSnapshotExists() {
        var store = new MemoryStore(null);
        var result = new BookRecovery(store).recover("book-journal", offsets -> {
            assertTrue(offsets.isEmpty());
            return new ReplayResult(List.of(accepted("buyer", "buy", "1", 90, 3, 1)), Map.of(0, 1L));
        });
        assertFalse(result.snapshotRestored());
        assertEquals(1, result.state().openOrders().size());
    }

    static CommandMessage accepted(String account, String id, String side, long price, long quantity, long sequence) {
        return new CommandMessage("book-journal", "BTC/BRL",
            "8=FIX.4.4|35=U4|49=engine|56=engine|1=%s|11=%s|55=BTC/BRL|54=%s|44=%d|38=%d|10003=%d|10004=2026-10-02T12:00:00Z|"
                .formatted(account, id, side, price, quantity, sequence));
    }

    private static final class MemoryStore implements BookSnapshotStore {
        private SnapshotCheckpoint checkpoint;
        private MemoryStore(SnapshotCheckpoint checkpoint) { this.checkpoint = checkpoint; }
        public Optional<SnapshotCheckpoint> loadLatestValid(String topic) { return Optional.ofNullable(checkpoint); }
        public void save(SnapshotCheckpoint checkpoint) { this.checkpoint = checkpoint; }
    }
}
