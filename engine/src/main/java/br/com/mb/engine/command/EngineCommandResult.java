package br.com.mb.engine.command;

import java.util.List;

public record EngineCommandResult(boolean accepted, String key, String detail, List<String> eventFixMessages) {

    public EngineCommandResult {
        eventFixMessages = List.copyOf(eventFixMessages);
    }

    public static EngineCommandResult accepted(String key, String commandType, List<String> eventFixMessages) {
        return new EngineCommandResult(true, key, commandType, eventFixMessages);
    }

    public static EngineCommandResult rejected(String key, String reason, List<String> eventFixMessages) {
        return new EngineCommandResult(false, key, reason, eventFixMessages);
    }

    public String line() {
        if (accepted) {
            return "ACCEPTED key=%s command=%s".formatted(key, detail);
        }
        return "REJECTED key=%s reason=%s".formatted(key, detail);
    }
}
