package br.com.mb.engine.domain;

public record Order(
    AccountId accountId,
    ClientOrderId clientOrderId,
    Instrument instrument,
    Side side,
    long price,
    long quantity
) {

    public Order {
        if (price <= 0) {
            throw new InvalidOrderException("price must be positive");
        }
        if (quantity <= 0) {
            throw new InvalidOrderException("quantity must be positive");
        }
    }
}
