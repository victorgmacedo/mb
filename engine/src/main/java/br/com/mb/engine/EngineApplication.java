package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.http.BookQueryServer;
import br.com.mb.engine.http.BookViews;
import br.com.mb.engine.journal.KafkaBookJournal;
import br.com.mb.engine.journal.KafkaSettlementJournal;
import br.com.mb.engine.snapshot.BookRecovery;
import br.com.mb.engine.snapshot.PostgresBookSnapshotStore;
import br.com.mb.ledger.jdbc.PostgresLedgerFactory;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class EngineApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(EngineApplication.class);

    private EngineApplication() {
    }

    public static void main(String[] args) throws IOException {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-engine"));
        var config = EngineConfig.fromEnvironment(environment);
        var consumer = KafkaCommandConsumer.connect(
            config.bootstrapServers(),
            config.consumerGroupId(),
            config.commandsTopic()
        );
        var eventPublisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-engine-events");
        var settlementPublisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-engine-settlements");
        var bookJournalPublisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-engine-book-journal");
        var recoveredState = recoverBook(config);
        var views = new BookViews();
        views.publish(recoveredState);
        var queryServer = new BookQueryServer(environment.getOrDefault("ENGINE_BOOK_HOST", "127.0.0.1"),
            Integer.parseInt(environment.getOrDefault("ENGINE_BOOK_PORT", "8081")), views);
        queryServer.start();
        var ledger = PostgresLedgerFactory.create();
        var settlementJournal = new KafkaSettlementJournal(settlementPublisher, config.settlementsTopic());
        var bookJournal = new KafkaBookJournal(bookJournalPublisher, config.bookJournalTopic());
        var handler = new EngineCommandHandler(
            eventPublisher,
            config.eventsTopic(),
            line -> LOG.info("engine.command.handled", "result", line),
            ledger,
            settlementJournal,
            bookJournal,
            recoveredState
        );

        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("engine.shutdown");
            consumer.requestStop();
            try {
                stopped.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));

        LOG.info(
            "engine.start",
            "commands_topic", config.commandsTopic(),
            "events_topic", config.eventsTopic(),
            "settlements_topic", config.settlementsTopic(),
            "book_journal_topic", config.bookJournalTopic(),
            "consumer_group_id", config.consumerGroupId(),
            "bootstrap_servers", config.bootstrapServers()
        );
        try {
            while (!Thread.currentThread().isInterrupted() && !consumer.isStopping()) {
                consumer.poll(message -> {
                    handler.handle(message);
                    views.publish(recoveredState);
                });
            }
        } finally {
            try {
                consumer.close();
                queryServer.close();
                eventPublisher.close();
                settlementPublisher.close();
                bookJournalPublisher.close();
            } finally {
                stopped.countDown();
            }
        }
    }

    private static EngineState recoverBook(EngineConfig config) {
        var store = PostgresBookSnapshotStore.fromEnvironment(System.getenv());
        store.initialize();
        try (var replayer = KafkaTopicReplayer.connect(config.bootstrapServers(), "mb-engine-book-recovery")) {
            var recovery = new BookRecovery(store).recover(config.bookJournalTopic(),
                offsets -> replayer.replay(config.bookJournalTopic(), offsets));
            var state = recovery.state();
            LOG.info(
                "engine.book.recovered",
                "book_journal_topic", config.bookJournalTopic(),
                "messages", recovery.replayedMessages(),
                "snapshot_restored", recovery.snapshotRestored(),
                "open_orders", state.openOrders().size()
            );
            return state;
        }
    }
}
