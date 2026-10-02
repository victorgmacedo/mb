package br.com.mb.engine.command;

public record EngineCommandResult(boolean accepted, String key, String detail) {

    public static EngineCommandResult accepted(String key, String messageType) {
        return new EngineCommandResult(true, key, messageType);
    }

    public static EngineCommandResult rejected(String key, String reason) {
        return new EngineCommandResult(false, key, reason);
    }

    public String line() {
        if (accepted) {
            return "ACCEPTED key=%s type=%s".formatted(key, detail);
        }
        return "REJECTED key=%s reason=%s".formatted(key, detail);
    }
}
