package br.com.mb.ledger;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.ledger.config.LedgerSettlementConfig;
import br.com.mb.ledger.jpa.PostgresLedgerFactory;
import br.com.mb.ledger.settlement.LedgerSettlementHandler;

public final class LedgerSettlementApplication {

    private LedgerSettlementApplication() {
    }

    public static void main(String[] args) {
        var config = LedgerSettlementConfig.fromEnvironment(System.getenv());
        var consumer = KafkaCommandConsumer.connect(
            config.bootstrapServers(),
            config.consumerGroupId(),
            config.settlementsTopic()
        );
        var handler = new LedgerSettlementHandler(PostgresLedgerFactory.create(), System.out::println);

        Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));

        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }
}
