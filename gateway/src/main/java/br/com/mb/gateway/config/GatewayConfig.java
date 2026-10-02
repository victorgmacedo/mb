package br.com.mb.gateway.config;

import java.util.Map;

public record GatewayConfig(String host, int port, String bootstrapServers, String commandsTopic) {

    public static GatewayConfig fromEnvironment(Map<String, String> environment) {
        return new GatewayConfig(
            environment.getOrDefault("GATEWAY_HOST", "0.0.0.0"),
            Integer.parseInt(environment.getOrDefault("GATEWAY_PORT", "8080")),
            environment.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            environment.getOrDefault("KAFKA_COMMANDS_TOPIC", "commands")
        );
    }
}
