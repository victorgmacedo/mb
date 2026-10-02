package br.com.mb.ledger;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.ledger.config.LedgerSettlementConfig;
import br.com.mb.ledger.jpa.PostgresLedgerFactory;
import br.com.mb.ledger.settlement.LedgerSettlementHandler;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;

public final class LedgerSettlementApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(LedgerSettlementApplication.class);

    private LedgerSettlementApplication() {
    }

    public static void main(String[] args) {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-ledger-settlements"));
        var config = LedgerSettlementConfig.fromEnvironment(environment);
        var consumer = KafkaCommandConsumer.connect(
            config.bootstrapServers(),
            config.consumerGroupId(),
            config.settlementsTopic()
        );
        var handler = new LedgerSettlementHandler(
            PostgresLedgerFactory.create(),
            line -> LOG.info("ledger.settlement.handled", "result", line)
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("ledger.settlement.shutdown");
            consumer.close();
        }));

        LOG.info(
            "ledger.settlement.start",
            "settlements_topic", config.settlementsTopic(),
            "consumer_group_id", config.consumerGroupId(),
            "bootstrap_servers", config.bootstrapServers()
        );
        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }
}
