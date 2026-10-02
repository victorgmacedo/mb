package br.com.mb.shared.model;

import java.util.Objects;

public record Asset(String symbol) {

    public Asset {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
    }
}
