package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;
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
        var ledger = PostgresLedgerFactory.create();
        var settlementJournal = new KafkaSettlementJournal(settlementPublisher, config.settlementsTopic());
        var handler = new EngineCommandHandler(eventPublisher, config.eventsTopic(), System.out::println, ledger, settlementJournal);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            eventPublisher.close();
            settlementPublisher.close();
        }));

        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }
}
