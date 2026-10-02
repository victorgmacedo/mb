package br.com.mb.engine.journal;

import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.ledger.BalanceReservations;
import java.util.Objects;

public final class SynchronousLedgerJournal implements ExecutionJournal {

    private final BalanceReservations balanceReservations;

    public SynchronousLedgerJournal(BalanceReservations balanceReservations) {
        this.balanceReservations = Objects.requireNonNull(balanceReservations, "balanceReservations must not be null");
    }

    @Override
    public void append(Order takerOrder, PlacementResult placement) {
        balanceReservations.settle(takerOrder, placement);
    }
}
