package br.com.mb.engine.config;

import java.util.Map;

public record EngineConfig(String bootstrapServers, String commandsTopic, String consumerGroupId) {

    public static EngineConfig fromEnvironment(Map<String, String> environment) {
        return new EngineConfig(
            environment.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            environment.getOrDefault("KAFKA_COMMANDS_TOPIC", "commands"),
            environment.getOrDefault("ENGINE_CONSUMER_GROUP_ID", "mb-engine")
        );
    }
}
