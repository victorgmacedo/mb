package br.com.mb.engine.snapshot;

import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.BookJournalReplayer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class BookRecovery {

    private final BookSnapshotStore store;

    public BookRecovery(BookSnapshotStore store) {
        this.store = store;
    }

    public RecoveryResult recover(String topic,
                                  Function<Map<Integer, Long>, KafkaTopicReplayer.ReplayResult> replay) {
        var checkpoint = store.loadLatestValid(topic);
        var state = checkpoint.map(saved -> new BookSnapshotRestorer().restore(saved.books()))
            .orElseGet(EngineState::new);
        var journal = replay.apply(checkpoint.map(SnapshotCheckpoint::nextOffsets).orElseGet(Map::of));
        new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(state, journal.messages());
        var codec = new BookSnapshotCodec();
        var saved = new SnapshotCheckpoint(UUID.randomUUID(), topic, Instant.now(), journal.nextOffsets(),
            state.books().stream().map(book -> codec.snapshotFrom(book, state.lastEntrySequence())).toList());
        store.save(saved);
        return new RecoveryResult(state, journal.messages().size(), checkpoint.isPresent());
    }

    public record RecoveryResult(EngineState state, int replayedMessages, boolean snapshotRestored) {
    }
}
