package br.com.mb.engine.domain;

import br.com.mb.engine.book.OrderBook;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.book.BookOrderView;
import br.com.mb.engine.command.CancelOrderCommand;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

public final class EngineState {

    private final Map<Instrument, OrderBook> books = new HashMap<>();
    private final Map<ClientOrderId, OrderBook> orderLocations = new HashMap<>();
    private long nextEntrySequence = 1;

    public PlacementResult place(Order order) {
        return place(order, nextEntrySequence(), Instant.now());
    }

    public PlacementResult place(Order order, long entrySequence, Instant enteredAt) {
        var book = books.computeIfAbsent(order.instrument(), OrderBook::new);
        var result = book.place(order, entrySequence, enteredAt);
        advanceEntrySequencePast(entrySequence);
        result.trades().stream()
            .filter(trade -> trade.makerLeavesQuantity() == 0)
            .forEach(trade -> orderLocations.remove(trade.makerClientOrderId()));
        result.restingOrder().ifPresent(restingOrder -> orderLocations.put(restingOrder.clientOrderId(), book));
        return result;
    }

    public BookOrder cancel(CancelOrderCommand command) {
        var originalClientOrderId = new ClientOrderId(command.originalClientOrderId());
        var book = orderLocations.remove(originalClientOrderId);
        if (book == null) {
            throw new InvalidOrderException("open order not found: " + originalClientOrderId.value());
        }
        return book.cancel(originalClientOrderId);
    }

    public boolean hasOpenOrder(ClientOrderId clientOrderId) {
        return orderLocations.containsKey(clientOrderId);
    }

    public Optional<BookOrder> openOrder(ClientOrderId clientOrderId) {
        var book = orderLocations.get(clientOrderId);
        if (book == null) {
            return Optional.empty();
        }
        return book.find(clientOrderId);
    }

    public OrderBook book(Instrument instrument) {
        return books.computeIfAbsent(instrument, OrderBook::new);
    }

    public List<BookOrderView> openOrders() {
        return books.values().stream()
            .flatMap(book -> book.openOrders().stream())
            .sorted(Comparator
                .comparing(BookOrderView::instrument)
                .thenComparing(order -> order.side().name())
                .thenComparing(BookOrderView::price)
                .thenComparing(BookOrderView::clientOrderId))
            .toList();
    }

    public long nextEntrySequence() {
        return nextEntrySequence++;
    }

    public long lastEntrySequence() {
        return nextEntrySequence - 1;
    }

    public List<OrderBook> books() {
        return books.values().stream().sorted(Comparator.comparing(book -> book.instrument().symbol())).toList();
    }

    public void restoreEntrySequence(long lastEntrySequence) {
        if (lastEntrySequence < 0 || lastEntrySequence == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid last entry sequence");
        }
        advanceEntrySequencePast(lastEntrySequence);
    }

    private void advanceEntrySequencePast(long entrySequence) {
        if (entrySequence >= nextEntrySequence) {
            nextEntrySequence = entrySequence + 1;
        }
    }
}
