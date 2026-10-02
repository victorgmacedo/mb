package br.com.mb.engine.book;

import java.util.List;
import java.util.Optional;

public record PlacementResult(List<Trade> trades, Optional<BookOrder> restingOrder) {

    public PlacementResult {
        trades = List.copyOf(trades);
    }

    public boolean fullyFilled() {
        return !trades.isEmpty() && restingOrder.isEmpty();
    }

    public boolean partiallyFilled() {
        return !trades.isEmpty() && restingOrder.isPresent();
    }
}
