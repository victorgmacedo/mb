package br.com.mb.engine.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InstrumentCatalog {

    private final Map<String, Instrument> instruments;

    private InstrumentCatalog(Map<String, Instrument> instruments) {
        this.instruments = Map.copyOf(instruments);
    }

    public static InstrumentCatalog defaultCatalog() {
        return new InstrumentCatalog(Map.of(
            "BTC/BRL", new Instrument("BTC/BRL"),
            "ETH/BRL", new Instrument("ETH/BRL"),
            "ETH/BTC", new Instrument("ETH/BTC")
        ));
    }

    public Optional<Instrument> findBySymbol(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(instruments.get(symbol));
    }
}
