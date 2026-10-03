package br.com.mb.engine.snapshot;

import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import java.util.Map;
import java.util.function.Function;

/** Retains journal-derived state between checkpoints; accessed by one snapshot thread. */
public final class BookSnapshotSession {

    private final BookRecovery recovery;
    private final String topic;
    private BookRecovery.RecoveryResult current;
    private boolean failed;

    public BookSnapshotSession(BookSnapshotStore store, String topic) {
        this.recovery = new BookRecovery(store);
        this.topic = topic;
    }

    public BookRecovery.RecoveryResult checkpoint(
        Function<Map<Integer, Long>, KafkaTopicReplayer.ReplayResult> replay
    ) {
        if (failed) {
            throw new IllegalStateException("Snapshot session failed; restart from a durable checkpoint");
        }
        try {
            current = current == null
                ? recovery.recover(topic, replay)
                : recovery.advance(topic, current.state(), current.nextOffsets(), replay, false);
            return current;
        } catch (RuntimeException exception) {
            // Replay can partially mutate memory. Never retry against that state.
            failed = true;
            throw exception;
        }
    }
}
