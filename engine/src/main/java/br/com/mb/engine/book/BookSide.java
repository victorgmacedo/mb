package br.com.mb.engine.book;

import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

final class BookSide {

    private final NavigableMap<Long, PriceLevel> levels;

    private BookSide(NavigableMap<Long, PriceLevel> levels) {
        this.levels = levels;
    }

    static BookSide bids() {
        return new BookSide(new TreeMap<>(Comparator.reverseOrder()));
    }

    static BookSide asks() {
        return new BookSide(new TreeMap<>());
    }

    void add(BookOrder order) {
        levels.computeIfAbsent(order.price(), PriceLevel::new).append(order);
    }

    void remove(BookOrder order) {
        var level = levels.get(order.price());
        if (level == null) {
            return;
        }

        level.remove(order);
        if (level.isEmpty()) {
            levels.remove(order.price());
        }
    }

    Optional<PriceLevel> bestLevel() {
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(levels.firstEntry().getValue());
    }

    void validateSelfTrade(Order order) {
        var remaining = order.quantity();
        for (var level : levels.values()) {
            var crosses = order.side() == Side.BUY
                ? order.price() >= level.price()
                : order.price() <= level.price();
            if (!crosses) {
                return;
            }
            for (var maker = level.head(); maker != null; maker = maker.next) {
                if (order.accountId().equals(maker.accountId())) {
                    throw new InvalidOrderException("self-trade prevented: " + maker.clientOrderId().value());
                }
                remaining -= Math.min(remaining, maker.remainingQuantity());
                if (remaining == 0) {
                    return;
                }
            }
        }
    }

    List<BookOrder> openOrders() {
        return levels.values().stream()
            .flatMap(level -> level.orders().stream())
            .toList();
    }

    List<BookPriceLevelView> priceLevels() {
        return levels.values().stream()
            .map(BookPriceLevelView::from)
            .toList();
    }
}
