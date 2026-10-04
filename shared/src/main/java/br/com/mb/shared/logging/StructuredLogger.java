package br.com.mb.shared.logging;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

public final class StructuredLogger {

    private final Logger logger;

    private StructuredLogger(Class<?> source) {
        this.logger = LoggerFactory.getLogger(source);
    }

    public static StructuredLogger forClass(Class<?> source) {
        return new StructuredLogger(source);
    }

    public void info(String event, Object... keyValues) {
        var builder = logger.atInfo().addKeyValue("event", event);
        addKeyValues(builder, keyValues).log(event);
    }

    public void error(String event, Throwable exception, Object... keyValues) {
        var builder = logger.atError().setCause(exception).addKeyValue("event", event);
        addKeyValues(builder, keyValues).log(event);
    }

    private static LoggingEventBuilder addKeyValues(
        LoggingEventBuilder builder,
        Object... keyValues
    ) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("structured log keyValues must contain key/value pairs");
        }

        for (var index = 0; index < keyValues.length; index += 2) {
            var key = Objects.toString(keyValues[index]);
            builder.addKeyValue(key, keyValues[index + 1]);
        }
        return builder;
    }
}
