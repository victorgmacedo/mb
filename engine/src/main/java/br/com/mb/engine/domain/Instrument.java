package br.com.mb.engine.domain;

import java.util.Objects;

public record Instrument(String symbol) {

    public Instrument {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("instrument symbol must not be blank");
        }
    }
}
