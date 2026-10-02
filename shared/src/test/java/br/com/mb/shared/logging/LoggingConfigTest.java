package br.com.mb.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LoggingConfigTest {

    @Test
    void usesOtelServiceNameWhenPresent() {
        var serviceName = LoggingConfig.serviceName(
            Map.of("OTEL_SERVICE_NAME", "custom-engine", "SERVICE_NAME", "legacy-engine"),
            "default-engine"
        );

        assertEquals("custom-engine", serviceName);
    }

    @Test
    void fallsBackToServiceName() {
        var serviceName = LoggingConfig.serviceName(Map.of("SERVICE_NAME", "legacy-engine"), "default-engine");

        assertEquals("legacy-engine", serviceName);
    }

    @Test
    void fallsBackToDefaultName() {
        var serviceName = LoggingConfig.serviceName(Map.of(), "default-engine");

        assertEquals("default-engine", serviceName);
    }
}
