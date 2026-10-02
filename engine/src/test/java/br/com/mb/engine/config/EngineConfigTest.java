package br.com.mb.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineConfigTest {

    @Test
    void readsConfigurationFromEnvironment() {
        var config = EngineConfig.fromEnvironment(Map.of(
            "KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
            "KAFKA_COMMANDS_TOPIC", "custom-commands",
            "ENGINE_CONSUMER_GROUP_ID", "engine-test"
        ));

        assertEquals("kafka:19092", config.bootstrapServers());
        assertEquals("custom-commands", config.commandsTopic());
        assertEquals("engine-test", config.consumerGroupId());
    }

    @Test
    void usesLocalDefaults() {
        var config = EngineConfig.fromEnvironment(Map.of());

        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("commands", config.commandsTopic());
        assertEquals("mb-engine", config.consumerGroupId());
    }
}
