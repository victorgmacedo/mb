package br.com.mb.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TelemetryContextTest {

    @Test
    void restoresPreviousContextAfterTraceScope() {
        MDC.clear();
        MDC.put(TelemetryContext.SERVICE_NAME, "mb-engine");

        try (var ignored = TelemetryContext.withTrace("trace-1", "span-1")) {
            assertEquals("mb-engine", MDC.get(TelemetryContext.SERVICE_NAME));
            assertEquals("trace-1", MDC.get(TelemetryContext.TRACE_ID));
            assertEquals("span-1", MDC.get(TelemetryContext.SPAN_ID));
        }

        assertEquals("mb-engine", MDC.get(TelemetryContext.SERVICE_NAME));
        assertNull(MDC.get(TelemetryContext.TRACE_ID));
        assertNull(MDC.get(TelemetryContext.SPAN_ID));
        MDC.clear();
    }
}
