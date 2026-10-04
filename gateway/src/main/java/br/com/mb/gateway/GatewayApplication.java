package br.com.mb.gateway;

import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import br.com.mb.gateway.http.GatewayHttpServer;
import br.com.mb.ledger.jooq.PostgresLedgerFactory;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;
import java.net.URI;

public final class GatewayApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(GatewayApplication.class);

    private GatewayApplication() {
    }

    public static void main(String[] args) {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-gateway"));
        var config = GatewayConfig.fromEnvironment(environment);
        var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-gateway");
        var server = new GatewayHttpServer(config, publisher, PostgresLedgerFactory.create(),
            URI.create(environment.getOrDefault("ENGINE_BOOK_URL", "http://127.0.0.1:8081")));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("gateway.shutdown");
            server.stop();
            publisher.close();
        }));

        LOG.info(
            "gateway.start",
            "host", config.host(),
            "port", config.port(),
            "commands_topic", config.commandsTopic(),
            "bootstrap_servers", config.bootstrapServers()
        );
        server.start();
    }
}
