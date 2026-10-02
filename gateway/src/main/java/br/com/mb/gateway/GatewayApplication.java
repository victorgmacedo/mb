package br.com.mb.gateway;

import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import br.com.mb.gateway.http.GatewayHttpServer;
import br.com.mb.shared.logging.LoggingConfig;
import br.com.mb.shared.logging.StructuredLogger;
import br.com.mb.shared.logging.TelemetryContext;

public final class GatewayApplication {

    private static final StructuredLogger LOG = StructuredLogger.forClass(GatewayApplication.class);

    private GatewayApplication() {
    }

    static void main(String[] args) {
        var environment = System.getenv();
        TelemetryContext.setServiceName(LoggingConfig.serviceName(environment, "mb-gateway"));
        var config = GatewayConfig.fromEnvironment(environment);
        var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-gateway");
        var server = new GatewayHttpServer(config, publisher);

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
