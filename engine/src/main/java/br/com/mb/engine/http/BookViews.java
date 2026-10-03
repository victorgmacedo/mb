package br.com.mb.engine.http;

import br.com.mb.engine.book.BookPriceLevelView;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.shared.http.HttpSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Only the command thread publishes; readers observe complete, immutable JSON snapshots. */
public final class BookViews {

    private volatile Map<String, String> books = Map.of();

    public void publish(EngineState state) {
        var updated = new HashMap<String, String>();
        for (var instrument : InstrumentCatalog.defaultCatalog().instruments()) {
            var book = state.book(instrument);
            updated.put(instrument.symbol(), "{\"instrument\":" + HttpSupport.quote(instrument.symbol())
                + ",\"bids\":" + levels(book.bidLevels()) + ",\"asks\":" + levels(book.askLevels()) + "}");
        }
        books = Map.copyOf(updated);
    }

    public Optional<String> find(String instrument) {
        return Optional.ofNullable(books.get(instrument));
    }

    private static String levels(List<BookPriceLevelView> levels) {
        return levels.stream().map(level -> "{\"price\":" + level.price() + ",\"orders\":"
            + level.orders().stream().map(order -> "{\"accountId\":" + HttpSupport.quote(order.accountId())
                + ",\"clientOrderId\":" + HttpSupport.quote(order.clientOrderId())
                + ",\"remainingQuantity\":" + order.remainingQuantity()
                + ",\"entrySequence\":" + order.entrySequence()
                + ",\"enteredAt\":" + HttpSupport.quote(order.enteredAt().toString()) + "}")
                .collect(Collectors.joining(",", "[", "]")) + "}")
            .collect(Collectors.joining(",", "[", "]"));
    }
}
