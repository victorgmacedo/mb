package br.com.mb.engine.domain;

import br.com.mb.shared.model.Asset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InstrumentCatalog {

    private final Map<String, ListedInstrument> instruments;

    private InstrumentCatalog(Map<String, ListedInstrument> instruments) {
        this.instruments = Map.copyOf(instruments);
    }

    public static InstrumentCatalog defaultCatalog() {
        return new InstrumentCatalog(Map.of(
            "BTC/BRL", listed("BTC/BRL", "BTC", "BRL"),
            "ETH/BRL", listed("ETH/BRL", "ETH", "BRL"),
            "ETH/BTC", listed("ETH/BTC", "ETH", "BTC")
        ));
    }

    public Optional<Instrument> findBySymbol(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(instruments.get(symbol)).map(ListedInstrument::instrument);
    }

    public Optional<ListedInstrument> findListing(Instrument instrument) {
        Objects.requireNonNull(instrument, "instrument must not be null");
        return Optional.ofNullable(instruments.get(instrument.symbol()));
    }

    public List<Instrument> instruments() {
        return instruments.values().stream().map(ListedInstrument::instrument)
            .sorted(Comparator.comparing(Instrument::symbol)).toList();
    }

    private static ListedInstrument listed(String symbol, String baseAsset, String quoteAsset) {
        return new ListedInstrument(new Instrument(symbol), new Asset(baseAsset), new Asset(quoteAsset));
    }
}
