package br.com.mb.engine.command;

public record NewOrderSingleCommand(
    String accountId,
    String clientOrderId,
    String instrument,
    OrderSide side,
    long price,
    long quantity
) implements EngineCommand {
}
