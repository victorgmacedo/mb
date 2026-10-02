package br.com.mb.engine.book;

import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class OrderBook {

    private final Instrument instrument;
    private final BookSide bids = BookSide.bids();
    private final BookSide asks = BookSide.asks();
    private final Map<ClientOrderId, BookOrder> ordersByClientOrderId = new HashMap<>();

    public OrderBook(Instrument instrument) {
        this.instrument = instrument;
    }

    public void add(Order order) {
        if (!instrument.equals(order.instrument())) {
            throw new InvalidOrderException("order instrument does not match book instrument");
        }
        if (ordersByClientOrderId.containsKey(order.clientOrderId())) {
            throw new InvalidOrderException("duplicate client order id: " + order.clientOrderId().value());
        }

        var bookOrder = BookOrder.from(order);
        sideFor(order.side()).add(bookOrder);
        ordersByClientOrderId.put(order.clientOrderId(), bookOrder);
    }

    public BookOrder cancel(ClientOrderId clientOrderId) {
        var order = ordersByClientOrderId.remove(clientOrderId);
        if (order == null) {
            throw new InvalidOrderException("open order not found: " + clientOrderId.value());
        }

        sideFor(order.side()).remove(order);
        return order;
    }

    public Optional<BookOrder> bestBid() {
        return bids.bestLevel().map(PriceLevel::head);
    }

    public Optional<BookOrder> bestAsk() {
        return asks.bestLevel().map(PriceLevel::head);
    }

    public Optional<BookOrder> find(ClientOrderId clientOrderId) {
        return Optional.ofNullable(ordersByClientOrderId.get(clientOrderId));
    }

    private BookSide sideFor(Side side) {
        return switch (side) {
            case BUY -> bids;
            case SELL -> asks;
        };
    }
}
