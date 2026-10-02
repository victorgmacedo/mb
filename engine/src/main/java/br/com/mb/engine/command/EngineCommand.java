package br.com.mb.engine.command;

public sealed interface EngineCommand permits NewOrderSingleCommand, CancelOrderCommand {
}
