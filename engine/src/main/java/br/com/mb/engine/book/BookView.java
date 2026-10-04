package br.com.mb.engine.book;

import java.util.List;

public record BookView(String instrument, List<BookPriceLevelView> bids, List<BookPriceLevelView> asks) {
    public BookView {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
