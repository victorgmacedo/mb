package br.com.mb.engine.book;

import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
        return place(order, 0, Instant.EPOCH);
    }

    public PlacementResult place(Order order, long entrySequence, Instant enteredAt) {
        return apply(plan(order, entrySequence, enteredAt));
    }

    public PlacementPlan plan(Order order, long entrySequence, Instant enteredAt) {
        if (!instrument.equals(order.instrument())) {
            throw new InvalidOrderException("order instrument does not match book instrument");
        }
        if (ordersByClientOrderId.containsKey(order.clientOrderId())) {
            throw new InvalidOrderException("duplicate client order id: " + order.clientOrderId().value());
        }
        return new PlacementPlan(order, entrySequence, enteredAt, oppositeSideFor(order.side()).previewTrades(order));
    }

    /** Apply immediately after validation/settlement, without another command between plan and apply. */
    public PlacementResult apply(PlacementPlan plan) {
        var taker = BookOrder.from(plan.order(), plan.entrySequence(), plan.enteredAt());
        for (var trade : plan.trades()) {
            var maker = ordersByClientOrderId.get(trade.makerClientOrderId());
            maker.fill(trade.quantity());
            taker.fill(trade.quantity());
            if (maker.isFilled()) {
                oppositeSideFor(taker.side()).remove(maker);
                ordersByClientOrderId.remove(maker.clientOrderId());
            }
        }
        if (!taker.isFilled()) {
            sideFor(taker.side()).add(taker);
            ordersByClientOrderId.put(taker.clientOrderId(), taker);
            return new PlacementResult(plan.trades(), Optional.of(taker));
        }
        return new PlacementResult(plan.trades(), Optional.empty());
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

    public Instrument instrument() {
        return instrument;
    }

    public List<BookPriceLevelView> bidLevels() {
        return bids.priceLevels();
    }

    public List<BookPriceLevelView> askLevels() {
        return asks.priceLevels();
    }

    public List<BookOrderView> openOrders() {
        var orders = new ArrayList<BookOrder>();
        orders.addAll(bids.openOrders());
        orders.addAll(asks.openOrders());
        return orders.stream()
            .map(BookOrderView::from)
            .sorted(Comparator
                .comparing(BookOrderView::instrument)
                .thenComparing(order -> order.side().name())
                .thenComparing(BookOrderView::price)
                .thenComparing(BookOrderView::clientOrderId))
            .toList();
    }

    private BookSide sideFor(Side side) {
        return switch (side) {
            case BUY -> bids;
            case SELL -> asks;
        };
    }

    private BookSide oppositeSideFor(Side side) {
        return switch (side) {
            case BUY -> asks;
            case SELL -> bids;
        };
    }
}
