package br.com.mb.gateway;

import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import br.com.mb.gateway.http.GatewayHttpServer;
import java.net.URI;

public final class GatewayApplication {
    private GatewayApplication() {}

    public static void main(String[] args) {
        var environment = System.getenv();
        var config = GatewayConfig.fromEnvironment(environment);
        var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-gateway");
        var server = new GatewayHttpServer(config, publisher,
            URI.create(environment.getOrDefault("ENGINE_URL", "http://127.0.0.1:8081")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); publisher.close(); }));
        server.start();
        System.out.println("Gateway ready on " + config.host() + ":" + server.port());
    }
}
