module br.com.mb.shared {
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;

    provides ch.qos.logback.classic.spi.Configurator with br.com.mb.shared.logging.JsonLogConfigurator;
    requires jdk.httpserver;

    exports br.com.mb.shared.http;

    exports br.com.mb.shared.fix;
    exports br.com.mb.shared.logging;
    exports br.com.mb.shared.model;
}
