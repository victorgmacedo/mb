package br.com.mb.gateway;

import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import br.com.mb.gateway.http.GatewayHttpServer;

public final class GatewayApplication {

    private GatewayApplication() {
    }

    public static void main(String[] args) {
        var config = GatewayConfig.fromEnvironment(System.getenv());
        var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-gateway");
        var server = new GatewayHttpServer(config, publisher);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            publisher.close();
        }));

        server.start();
    }
}
