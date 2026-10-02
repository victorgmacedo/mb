package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.snapshot.BookRecovery;
import br.com.mb.engine.snapshot.PostgresBookSnapshotStore;
import br.com.mb.engine.journal.KafkaBookJournal;
import br.com.mb.engine.journal.KafkaSettlementJournal;
import br.com.mb.ledger.jpa.PostgresLedgerFactory;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;

public final class EngineApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(EngineApplication.class);

    private EngineApplication() {
    }

    public static void main(String[] args) {
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("engine.shutdown");
            consumer.close();
            eventPublisher.close();
            settlementPublisher.close();
            bookJournalPublisher.close();
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
        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }

    private static br.com.mb.engine.domain.EngineState recoverBook(EngineConfig config) {
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
