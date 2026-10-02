package br.com.mb.engine.book;

import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.util.ArrayList;
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
        place(order);
    }

    public PlacementResult place(Order order) {
        if (!instrument.equals(order.instrument())) {
            throw new InvalidOrderException("order instrument does not match book instrument");
        }
        if (ordersByClientOrderId.containsKey(order.clientOrderId())) {
            throw new InvalidOrderException("duplicate client order id: " + order.clientOrderId().value());
        }

        var taker = BookOrder.from(order);
        var trades = match(taker);
        if (!taker.isFilled()) {
            sideFor(order.side()).add(taker);
            ordersByClientOrderId.put(order.clientOrderId(), taker);
            return new PlacementResult(trades, Optional.of(taker));
        }

        return new PlacementResult(trades, Optional.empty());
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

    private ArrayList<Trade> match(BookOrder taker) {
        var trades = new ArrayList<Trade>();
        var opposite = oppositeSideFor(taker.side());
        while (!taker.isFilled()) {
            var bestLevel = opposite.bestLevel();
            if (bestLevel.isEmpty() || !crosses(taker, bestLevel.get().price())) {
                break;
            }

            var maker = bestLevel.get().head();
            var quantity = Math.min(taker.remainingQuantity(), maker.remainingQuantity());
            maker.fill(quantity);
            taker.fill(quantity);
            trades.add(new Trade(
                maker.accountId(),
                maker.clientOrderId(),
                taker.accountId(),
                taker.clientOrderId(),
                maker.price(),
                quantity,
                maker.remainingQuantity(),
                taker.remainingQuantity()
            ));

            if (maker.isFilled()) {
                opposite.remove(maker);
                ordersByClientOrderId.remove(maker.clientOrderId());
            }
        }
        return trades;
    }

    private BookSide oppositeSideFor(Side side) {
        return switch (side) {
            case BUY -> asks;
            case SELL -> bids;
        };
    }

    private static boolean crosses(BookOrder taker, long makerPrice) {
        return switch (taker.side()) {
            case BUY -> taker.price() >= makerPrice;
            case SELL -> taker.price() <= makerPrice;
        };
    }
}
