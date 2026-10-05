package br.com.mb.engine.command.strategy;

import br.com.mb.engine.command.EngineEventFactory;
import br.com.mb.engine.command.NewOrderSingleCommand;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.ledger.BalanceReservations;
import java.util.List;

public final class NewOrderSingleStrategy implements EngineCommandStrategy<NewOrderSingleCommand> {
    private final OrderIntake intake;
    private final EngineState state;
    private final BalanceReservations reservations;
    private final EngineEventFactory events;

    public NewOrderSingleStrategy(OrderIntake intake, EngineState state, BalanceReservations reservations, EngineEventFactory events) {
        this.intake = intake;
        this.state = state;
        this.reservations = reservations;
        this.events = events;
    }

    @Override
    public List<String> execute(NewOrderSingleCommand command) {
        var plan = state.plan(intake.accept(command));
        reservations.settle(plan);
        return events.accepted(command, state.apply(plan));
    }
}
