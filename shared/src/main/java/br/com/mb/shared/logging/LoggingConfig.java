package br.com.mb.shared.logging;

import java.util.Map;

public final class LoggingConfig {

    private LoggingConfig() {
    }

    public static String serviceName(Map<String, String> environment, String defaultName) {
        return environment.getOrDefault("OTEL_SERVICE_NAME", environment.getOrDefault("SERVICE_NAME", defaultName));
    }
}
