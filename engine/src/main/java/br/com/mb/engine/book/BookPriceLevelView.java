package br.com.mb.engine.book;

import java.util.List;

public record BookPriceLevelView(
    long price,
    List<BookOrderView> orders
) {

    static BookPriceLevelView from(PriceLevel level) {
        return new BookPriceLevelView(
            level.price(),
            level.orders().stream()
                .map(BookOrderView::from)
                .toList()
        );
    }
}
