package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.snapshot.BookSnapshotSession;
import br.com.mb.engine.snapshot.PostgresBookSnapshotStore;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Builds checkpoints from the durable journal independently of the matching process. */
public final class BookSnapshotsApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(BookSnapshotsApplication.class);

    private BookSnapshotsApplication() {
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-book-snapshots"));
        var config = EngineConfig.fromEnvironment(environment);
        var interval = Long.parseLong(environment.getOrDefault("MB_BOOK_SNAPSHOT_INTERVAL_MS", "60000"));
        if (interval <= 0) {
            throw new IllegalArgumentException("MB_BOOK_SNAPSHOT_INTERVAL_MS must be positive");
        }
        var store = PostgresBookSnapshotStore.fromEnvironment(environment);
        store.initialize();
        var snapshots = new BookSnapshotSession(store, config.bookJournalTopic());
        try (var replayer = KafkaTopicReplayer.connect(config.bootstrapServers(), "mb-book-snapshots")) {
            var serviceName = LoggingConfig.serviceName(environment, "mb-book-snapshots");
            Runnable snapshot = () -> {
                TelemetryContext.setServiceName(serviceName);
                var result = snapshots.checkpoint(
                    offsets -> replayer.replay(config.bookJournalTopic(), offsets));
                LOG.info("engine.snapshot.saved", "replayed_messages", result.replayedMessages(),
                    "open_orders", result.state().openOrders().size());
            };
            if (Arrays.asList(args).contains("--once")) {
                snapshot.run();
                return;
            }
            runPeriodically(snapshot, interval);
        }
    }

    static void runPeriodically(Runnable snapshot, long interval) throws InterruptedException, ExecutionException {
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var task = scheduler.scheduleWithFixedDelay(snapshot, 0, interval, TimeUnit.MILLISECONDS);
            try {
                // A failed periodic task must terminate the process instead of leaving it idle.
                task.get();
            } finally {
                scheduler.shutdownNow();
            }
        }
    }
}
