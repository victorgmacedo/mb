package br.com.mb.engine.domain;

import java.util.Objects;

public record ClientOrderId(String value) {

    public ClientOrderId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("client order id must not be blank");
        }
    }
}
