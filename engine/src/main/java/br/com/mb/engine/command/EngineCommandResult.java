package br.com.mb.engine.command;

public record EngineCommandResult(boolean accepted, String key, String detail, String eventFixMessage) {

    public static EngineCommandResult accepted(String key, String commandType, String eventFixMessage) {
        return new EngineCommandResult(true, key, commandType, eventFixMessage);
    }

    public static EngineCommandResult rejected(String key, String reason, String eventFixMessage) {
        return new EngineCommandResult(false, key, reason, eventFixMessage);
    }

    public String line() {
        if (accepted) {
            return "ACCEPTED key=%s command=%s".formatted(key, detail);
        }
        return "REJECTED key=%s reason=%s".formatted(key, detail);
    }
}
