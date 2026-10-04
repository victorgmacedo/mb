package br.com.mb.engine.book;

import br.com.mb.engine.domain.Order;
import java.time.Instant;
import java.util.List;

/** Calculated fills, without changing the existing orders. Consumed immediately by the command thread. */
public record PlacementPlan(Order order, long entrySequence, Instant enteredAt, List<Trade> trades) {
    public PlacementPlan { trades = List.copyOf(trades); }
}
