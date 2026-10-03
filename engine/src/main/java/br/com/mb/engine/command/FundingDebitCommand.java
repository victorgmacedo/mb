package br.com.mb.engine.command;

public record FundingDebitCommand(String accountId, String clientOrderId, String asset, long amount) implements EngineCommand {
}
