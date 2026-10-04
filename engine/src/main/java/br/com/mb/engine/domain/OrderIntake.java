package br.com.mb.engine.domain;

import br.com.mb.engine.command.EngineCommand;
import br.com.mb.engine.command.NewOrderSingleCommand;

public final class OrderIntake {

    private final InstrumentCatalog instruments;

    public OrderIntake(InstrumentCatalog instruments) {
        this.instruments = instruments;
    }

    public Order accept(EngineCommand command) {
        return switch (command) {
            case NewOrderSingleCommand newOrder -> accept(newOrder);
            default -> throw new InvalidOrderException("command is not an order intake command");
        };
    }

    private Order accept(NewOrderSingleCommand command) {
        var instrument = instruments.findBySymbol(command.instrument())
            .orElseThrow(() -> new InvalidOrderException("unknown instrument: " + command.instrument()));

        return new Order(
            new AccountId(command.accountId()),
            new ClientOrderId(command.clientOrderId()),
            instrument,
            Side.fromCommandSide(command.side()),
            command.price(),
            command.quantity()
        );
    }
}
