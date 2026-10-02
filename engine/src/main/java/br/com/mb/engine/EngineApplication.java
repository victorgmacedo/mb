package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.commandlog.kafka.KafkaTopicReplayer;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.journal.BookJournalReplayer;
import br.com.mb.engine.journal.KafkaBookJournal;
import br.com.mb.engine.journal.KafkaSettlementJournal;
import br.com.mb.ledger.jpa.PostgresLedgerFactory;

public final class EngineApplication {

    private EngineApplication() {
    }

    public static void main(String[] args) {
        var config = EngineConfig.fromEnvironment(System.getenv());
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
            System.out::println,
            ledger,
            settlementJournal,
            bookJournal,
            recoveredState
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            eventPublisher.close();
            settlementPublisher.close();
            bookJournalPublisher.close();
        }));

        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }

    private static br.com.mb.engine.domain.EngineState recoverBook(EngineConfig config) {
        try (var replayer = KafkaTopicReplayer.connect(config.bootstrapServers(), "mb-engine-book-recovery")) {
            var messages = replayer.replay(config.bookJournalTopic());
            var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(messages);
            System.out.printf("RECOVERED bookJournalTopic=%s messages=%d openOrders=%d%n",
                config.bookJournalTopic(),
                messages.size(),
                state.openOrders().size()
            );
            return state;
        }
    }
}
