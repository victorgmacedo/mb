package br.com.mb.engine.config;

import java.util.Map;

public record EngineConfig(String bootstrapServers, String commandsTopic, String eventsTopic) {
    public static EngineConfig fromEnvironment(Map<String, String> environment) {
        return new EngineConfig(environment.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            environment.getOrDefault("KAFKA_COMMANDS_TOPIC", "commands"),
            environment.getOrDefault("KAFKA_EVENTS_TOPIC", "events"));
    }
}
