package br.com.mb.shared.logging;

import br.com.mb.shared.http.HttpSupport;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** JSON stdout without Jackson or reflective XML configuration. */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    @Override
    public byte[] headerBytes() { return null; }

    @Override
    public byte[] footerBytes() { return null; }

    @Override
    public byte[] encode(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("severity_text", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("thread", event.getThreadName());
        fields.put("deployment.environment", System.getenv().getOrDefault("DEPLOYMENT_ENVIRONMENT", "local"));
        fields.putAll(event.getMDCPropertyMap());
        if (event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) fields.put(pair.key, pair.value);
        }
        fields.put("message", event.getFormattedMessage());
        if (event.getThrowableProxy() != null) fields.put("stack_trace", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        var json = fields.entrySet().stream().map(entry -> HttpSupport.quote(entry.getKey()) + ":" + value(entry.getValue()))
            .collect(Collectors.joining(",", "{", "}\n"));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long) return value.toString();
        return HttpSupport.quote(value.toString());
    }
}
