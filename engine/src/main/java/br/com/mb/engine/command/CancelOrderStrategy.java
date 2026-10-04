package br.com.mb.engine.command;

import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.journal.BookJournal;
import br.com.mb.engine.ledger.BalanceReservations;
import java.util.List;
import java.util.Objects;

public final class CancelOrderStrategy implements EngineCommandStrategy<CancelOrderCommand> {
    private final EngineState state;
    private final BalanceReservations reservations;
    private final BookJournal bookJournal;
    private final EngineEventFactory events;

    public CancelOrderStrategy(EngineState state, BalanceReservations reservations, BookJournal bookJournal,
                               EngineEventFactory events) {
        this.state = Objects.requireNonNull(state);
        this.reservations = Objects.requireNonNull(reservations);
        this.bookJournal = Objects.requireNonNull(bookJournal);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public List<String> execute(CancelOrderCommand command) {
        var originalId = new ClientOrderId(command.originalClientOrderId());
        var order = state.openOrder(originalId)
            .orElseThrow(() -> new InvalidOrderException("open order not found: " + originalId.value()));
        if (!order.accountId().value().equals(command.accountId())) {
            throw new InvalidOrderException("order belongs to another account");
        }
        bookJournal.appendCancelled(order);
        reservations.release(state.cancel(command));
        return events.accepted(command);
    }
}
