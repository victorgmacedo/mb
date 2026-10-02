package br.com.mb.engine.domain;

import br.com.mb.engine.book.OrderBook;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.command.CancelOrderCommand;
import java.util.HashMap;
import java.util.Map;

public final class EngineState {

    private final Map<Instrument, OrderBook> books = new HashMap<>();
    private final Map<ClientOrderId, OrderBook> orderLocations = new HashMap<>();

    public PlacementResult place(Order order) {
        var book = books.computeIfAbsent(order.instrument(), OrderBook::new);
        var result = book.place(order);
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

    public OrderBook book(Instrument instrument) {
        return books.computeIfAbsent(instrument, OrderBook::new);
    }
}
