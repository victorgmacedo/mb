package br.com.mb.ledger;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.ledger.config.LedgerSettlementConfig;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.jooq.PostgresLedgerFactory;
import br.com.mb.ledger.settlement.LedgerSettlementHandler;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("ledger.settlement.shutdown");
            consumer.requestStop();
            try {
                stopped.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));

        LOG.info(
            "ledger.settlement.start",
            "settlements_topic", config.settlementsTopic(),
            "consumer_group_id", config.consumerGroupId(),
            "bootstrap_servers", config.bootstrapServers()
        );
        try {
            while (!Thread.currentThread().isInterrupted() && !consumer.isStopping()) {
                try {
                    consumer.poll(handler);
                } catch (LedgerException exception) {
                    LOG.info("ledger.settlement.retry", "reason", exception.getMessage());
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            try {
                consumer.close();
            } finally {
                stopped.countDown();
            }
        }
    }
}
