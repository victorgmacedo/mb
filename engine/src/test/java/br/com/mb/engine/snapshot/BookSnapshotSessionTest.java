package br.com.mb.engine.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer.ReplayResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookSnapshotSessionTest {

    @Test
    void retainsStateAndOffsetsAcrossFillsCancellationAndIdleCycles() {
        var store = new MemoryStore();
        var session = new BookSnapshotSession(store, "book-journal");
        var first = session.checkpoint(offsets -> {
            assertEquals(Map.of(), offsets);
            return new ReplayResult(List.of(BookRecoveryTest.accepted("seller", "sell", "2", 100, 10, 1)),
                Map.of(0, 1L));
        });
        var second = session.checkpoint(offsets -> {
            assertEquals(Map.of(0, 1L), offsets);
            return new ReplayResult(List.of(BookRecoveryTest.accepted("buyer", "buy", "1", 100, 4, 2)),
                Map.of(0, 2L));
        });
        assertSame(first.state(), second.state());
        assertEquals(6, second.state().openOrders().getFirst().remainingQuantity());
        session.checkpoint(offsets -> {
            assertEquals(Map.of(0, 2L), offsets);
            return new ReplayResult(List.of(new CommandMessage("book-journal", "BTC/BRL",
                "8=FIX.4.4|35=U5|1=seller|41=sell|55=BTC/BRL|")), Map.of(0, 3L));
        });
        var idle = session.checkpoint(offsets -> {
            assertEquals(Map.of(0, 3L), offsets);
            return new ReplayResult(List.of(), offsets);
        });
        assertSame(first.state(), idle.state());
        assertTrue(idle.state().openOrders().isEmpty());
        assertEquals(0, idle.replayedMessages());
        assertEquals(1, store.loads);
        assertEquals(4, store.saves);
        assertEquals(Map.of(0, 3L), store.saved.nextOffsets());
        assertEquals(2, store.saved.books().getFirst().lastEntrySequence());

        var restarted = new BookSnapshotSession(store, "book-journal").checkpoint(offsets -> {
            assertEquals(Map.of(0, 3L), offsets);
            return new ReplayResult(List.of(), offsets);
        });
        assertTrue(restarted.snapshotRestored());
        assertEquals(2, store.loads);
        assertNotSame(first.state(), restarted.state());
        assertEquals(idle.state().openOrders(), restarted.state().openOrders());
    }

    @Test
    void failedSavePreservesDurableOffsetsAndRequiresNewSession() {
        var store = new MemoryStore();
        var session = new BookSnapshotSession(store, "book-journal");
        session.checkpoint(offsets -> new ReplayResult(List.of(), Map.of(0, 0L)));
        store.failSave = true;
        assertThrows(IllegalStateException.class, () -> session.checkpoint(offsets ->
            new ReplayResult(List.of(BookRecoveryTest.accepted("seller", "sell", "2", 100, 10, 1)),
                Map.of(0, 1L))));
        assertEquals(Map.of(0, 0L), store.saved.nextOffsets());
        assertThrows(IllegalStateException.class, () -> session.checkpoint(offsets -> {
            fail("Failed session must not replay again");
            return null;
        }));
        store.failSave = false;
        var restarted = new BookSnapshotSession(store, "book-journal").checkpoint(offsets -> {
            assertEquals(Map.of(0, 0L), offsets);
            return new ReplayResult(List.of(BookRecoveryTest.accepted("seller", "sell", "2", 100, 10, 1)),
                Map.of(0, 1L));
        });
        assertEquals(1, restarted.state().openOrders().size());
    }

    private static final class MemoryStore implements BookSnapshotStore {
        private SnapshotCheckpoint saved;
        private int loads;
        private int saves;
        private boolean failSave;

        @Override
        public Optional<SnapshotCheckpoint> loadLatestValid(String topic) {
            loads++;
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(SnapshotCheckpoint checkpoint) {
            if (failSave) {
                throw new IllegalStateException("database unavailable");
            }
            saved = checkpoint;
            saves++;
        }
    }
}
