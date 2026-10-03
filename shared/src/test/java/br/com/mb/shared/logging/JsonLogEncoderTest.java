package br.com.mb.shared.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

class JsonLogEncoderTest {

    @Test
    void encodesContextStructuredValuesEscapedMessagesAndExceptions() {
        var context = new LoggerContext();
        var logger = context.getLogger("example");
        var event = new LoggingEvent("example", logger, Level.ERROR, "quote \" and newline\n", new IllegalStateException("failed"), null);
        event.setTimeStamp(0);
        event.setMDCPropertyMap(Map.of("service.name", "mb-engine", "trace_id", "abc"));
        event.addKeyValuePair(new KeyValuePair("quantity", 3));
        var encoder = new JsonLogEncoder();
        var json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"timestamp\":\"1970-01-01T00:00:00Z\""));
        assertTrue(json.contains("\"service.name\":\"mb-engine\""));
        assertTrue(json.contains("\"quantity\":3"));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\u000a"));
        assertTrue(json.contains("\"stack_trace\":"));
        assertTrue(json.endsWith("}\n"));
        context.stop();
    }
}
