package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.durable.DurableEngineStore;
import br.com.mb.engine.durable.OutboxDispatcher;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.BookJournalReplayer;
import br.com.mb.engine.http.BookQueryServer;
import br.com.mb.engine.http.BookViews;
import br.com.mb.ledger.jooq.PostgresLedgerFactory;
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
        var ledger = PostgresLedgerFactory.create(environment);
        var durable = new DurableEngineStore(ledger, config.commandsTopic(), config.eventsTopic(),
            config.bookJournalTopic(), config.settlementsTopic());
        durable.initialize();
        var recoveredState = durable.bootstrapWithHistory(() -> {
            try (var replayer = KafkaTopicReplayer.connect(config.bootstrapServers(), "mb-engine-migration-check")) {
                var journal = replayer.replay(config.bookJournalTopic());
                if (!journal.isEmpty() && !Boolean.parseBoolean(environment.getOrDefault("MB_ENGINE_IMPORT_LEGACY", "false"))) {
                    throw new IllegalStateException("Legacy journal exists: stop legacy writers, reconcile balances and migrate with MB_ENGINE_IMPORT_LEGACY=true");
                }
                var state = journal.isEmpty() ? new EngineState()
                    : new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(journal);
                return new DurableEngineStore.ImportedState(state, journal);
            }
        });
        LOG.info("engine.book.recovered", "source", "postgres", "open_orders", recoveredState.openOrders().size());
        var views = new BookViews();
        views.publish(recoveredState);
        var stopped = new CountDownLatch(1);
        try (var consumer = KafkaCommandConsumer.connect(config.bootstrapServers(), config.consumerGroupId(),
                 config.commandsTopic(), new KafkaCommandConsumer.PartitionListener() {
                     public void assigned(java.util.List<Integer> partitions) { durable.assigned(partitions); }
                     public void revoked(java.util.List<Integer> partitions) { durable.revoked(partitions); }
                 });
             var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-engine-outbox");
             var queryServer = new BookQueryServer(environment.getOrDefault("ENGINE_BOOK_HOST", "127.0.0.1"),
                 Integer.parseInt(environment.getOrDefault("ENGINE_BOOK_PORT", "8081")), views);
             var outbox = new OutboxDispatcher(durable, publisher,
                 exception -> LOG.info("engine.outbox.retry", "reason", exception.getMessage()))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("engine.shutdown");
                consumer.requestStop();
                try {
                    stopped.await(45, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            LOG.info("engine.start", "commands_topic", config.commandsTopic(), "events_topic", config.eventsTopic(),
                "settlements_topic", config.settlementsTopic(), "book_journal_topic", config.bookJournalTopic(),
                "consumer_group_id", config.consumerGroupId(), "bootstrap_servers", config.bootstrapServers());
            queryServer.start();
            outbox.start();
            while (!Thread.currentThread().isInterrupted() && !consumer.isStopping()) {
                consumer.poll(message -> {
                    var applied = durable.apply(message);
                    views.publish(applied.state());
                    LOG.info("engine.command.handled", "result", applied.result(), "duplicate", applied.duplicate());
                });
            }
        } finally {
            stopped.countDown();
        }
    }

}
