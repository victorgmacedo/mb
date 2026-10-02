package br.com.mb.shared.logging;

import java.util.Map;
import org.slf4j.MDC;

public final class TelemetryContext implements AutoCloseable {

    public static final String SERVICE_NAME = "service.name";
    public static final String TRACE_ID = "trace_id";
    public static final String SPAN_ID = "span_id";

    private final Map<String, String> previousContext;

    private TelemetryContext(Map<String, String> previousContext) {
        this.previousContext = previousContext;
    }

    public static void setServiceName(String serviceName) {
        MDC.put(SERVICE_NAME, serviceName);
    }

    public static TelemetryContext withTrace(String traceId, String spanId) {
        var previous = MDC.getCopyOfContextMap();
        putIfPresent(TRACE_ID, traceId);
        putIfPresent(SPAN_ID, spanId);
        return new TelemetryContext(previous);
    }

    @Override
    public void close() {
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}
