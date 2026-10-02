package br.com.mb.engine.book;

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

    List<BookOrder> openOrders() {
        return levels.values().stream()
            .flatMap(level -> level.orders().stream())
            .toList();
    }
}
