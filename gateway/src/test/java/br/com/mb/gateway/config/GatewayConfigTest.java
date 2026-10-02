package br.com.mb.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigTest {

    @Test
    void readsConfigurationFromEnvironment() {
        var config = GatewayConfig.fromEnvironment(Map.of(
            "GATEWAY_HOST", "127.0.0.1",
            "GATEWAY_PORT", "9090",
            "KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
            "KAFKA_COMMANDS_TOPIC", "custom-commands"
        ));

        assertEquals("127.0.0.1", config.host());
        assertEquals(9090, config.port());
        assertEquals("kafka:19092", config.bootstrapServers());
        assertEquals("custom-commands", config.commandsTopic());
    }

    @Test
    void usesLocalDefaults() {
        var config = GatewayConfig.fromEnvironment(Map.of());

        assertEquals("0.0.0.0", config.host());
        assertEquals(8080, config.port());
        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("commands", config.commandsTopic());
    }
}
