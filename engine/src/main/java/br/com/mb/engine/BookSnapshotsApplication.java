package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.snapshot.BookRecovery;
import br.com.mb.engine.snapshot.PostgresBookSnapshotStore;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;

/** Builds checkpoints from the durable journal independently of the matching process. */
public final class BookSnapshotsApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(BookSnapshotsApplication.class);

    private BookSnapshotsApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-book-snapshots"));
        var config = EngineConfig.fromEnvironment(environment);
        var interval = Long.parseLong(environment.getOrDefault("MB_BOOK_SNAPSHOT_INTERVAL_MS", "60000"));
        if (interval <= 0) {
            throw new IllegalArgumentException("MB_BOOK_SNAPSHOT_INTERVAL_MS must be positive");
        }
        var store = PostgresBookSnapshotStore.fromEnvironment(environment);
        store.initialize();
        var recovery = new BookRecovery(store);
        try (var replayer = KafkaTopicReplayer.connect(config.bootstrapServers(), "mb-book-snapshots")) {
            while (!Thread.currentThread().isInterrupted()) {
                var result = recovery.recover(config.bookJournalTopic(), offsets -> replayer.replay(config.bookJournalTopic(), offsets));
                LOG.info("engine.snapshot.saved", "replayed_messages", result.replayedMessages(),
                    "open_orders", result.state().openOrders().size());
                if (java.util.Arrays.asList(args).contains("--once")) {
                    return;
                }
                Thread.sleep(interval);
            }
        }
    }
}
