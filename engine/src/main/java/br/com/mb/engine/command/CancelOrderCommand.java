package br.com.mb.engine.command;

public record CancelOrderCommand(String accountId, String clientOrderId, String originalClientOrderId) implements EngineCommand {
}
