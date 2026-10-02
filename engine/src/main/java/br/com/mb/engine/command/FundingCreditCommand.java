package br.com.mb.engine.command;

public record FundingCreditCommand(
    String accountId,
    String clientOrderId,
    String asset,
    long amount
) implements EngineCommand {
}
