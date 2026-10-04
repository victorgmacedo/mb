package br.com.mb.engine.command;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.journal.BookJournal;
import br.com.mb.engine.journal.ExecutionJournal;
import br.com.mb.engine.ledger.BalanceReservations;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class NewOrderSingleStrategy implements EngineCommandStrategy<NewOrderSingleCommand> {
    private final OrderIntake orderIntake;
    private final EngineState state;
    private final BalanceReservations reservations;
    private final BookJournal bookJournal;
    private final ExecutionJournal executionJournal;
    private final EngineEventFactory events;

    public NewOrderSingleStrategy(OrderIntake orderIntake, EngineState state, BalanceReservations reservations,
                                  BookJournal bookJournal, ExecutionJournal executionJournal, EngineEventFactory events) {
        this.orderIntake = Objects.requireNonNull(orderIntake);
        this.state = Objects.requireNonNull(state);
        this.reservations = Objects.requireNonNull(reservations);
        this.bookJournal = Objects.requireNonNull(bookJournal);
        this.executionJournal = Objects.requireNonNull(executionJournal);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public List<String> execute(NewOrderSingleCommand command) {
        var order = orderIntake.accept(command);
        if (state.hasOpenOrder(order.clientOrderId())) {
            throw new InvalidOrderException("duplicate client order id: " + order.clientOrderId().value());
        }
        state.book(order.instrument()).validateSelfTrade(order);
        reservations.reserve(order);
        var entrySequence = state.nextEntrySequence();
        var enteredAt = Instant.now();
        bookJournal.appendAccepted(order, entrySequence, enteredAt);
        PlacementResult placement;
        try {
            placement = state.place(order, entrySequence, enteredAt);
        } catch (InvalidOrderException exception) {
            reservations.release(BookOrder.from(order));
            throw exception;
        }
        executionJournal.append(order, placement);
        return events.accepted(command, placement);
    }
}
