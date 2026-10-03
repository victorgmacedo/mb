package br.com.mb.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.spi.ContextAwareBase;

/** Direct configuration avoids reflective bean discovery during native startup. */
public final class JsonLogConfigurator extends ContextAwareBase implements Configurator {

    @Override
    public ExecutionStatus configure(LoggerContext context) {
        setContext(context);
        var encoder = new JsonLogEncoder();
        encoder.setContext(context);
        encoder.start();
        var appender = new ConsoleAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName("JSON_STDOUT");
        appender.setEncoder(encoder);
        appender.start();
        var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(System.getenv().getOrDefault("LOG_LEVEL", "INFO"), Level.INFO));
        root.addAppender(appender);
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }
}
