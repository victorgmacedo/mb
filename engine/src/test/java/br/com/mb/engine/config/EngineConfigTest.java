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
            "KAFKA_EVENTS_TOPIC", "custom-events",
            "KAFKA_SETTLEMENTS_TOPIC", "custom-settlements",
            "ENGINE_CONSUMER_GROUP_ID", "engine-test"
        ));

        assertEquals("kafka:19092", config.bootstrapServers());
        assertEquals("custom-commands", config.commandsTopic());
        assertEquals("custom-events", config.eventsTopic());
        assertEquals("custom-settlements", config.settlementsTopic());
        assertEquals("engine-test", config.consumerGroupId());
    }

    @Test
    void usesLocalDefaults() {
        var config = EngineConfig.fromEnvironment(Map.of());

        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("commands", config.commandsTopic());
        assertEquals("events", config.eventsTopic());
        assertEquals("settlements", config.settlementsTopic());
        assertEquals("mb-engine", config.consumerGroupId());
    }
}
