package br.com.mb.engine.book;

import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.time.Instant;
import java.util.Objects;

public final class BookOrder {

    private final AccountId accountId;
    private final ClientOrderId clientOrderId;
    private final Instrument instrument;
    private final Side side;
    private final long price;
    private final long entrySequence;
    private final Instant enteredAt;
    private long remainingQuantity;
    BookOrder previous;
    BookOrder next;

    private BookOrder(Order order, long entrySequence, Instant enteredAt) {
        this.accountId = order.accountId();
        this.clientOrderId = order.clientOrderId();
        this.instrument = order.instrument();
        this.side = order.side();
        this.price = order.price();
        this.entrySequence = entrySequence;
        this.enteredAt = Objects.requireNonNull(enteredAt, "enteredAt must not be null");
        this.remainingQuantity = order.quantity();
    }

    public static BookOrder from(Order order, long entrySequence, Instant enteredAt) {
        return new BookOrder(order, entrySequence, enteredAt);
    }

    public AccountId accountId() {
        return accountId;
    }

    public ClientOrderId clientOrderId() {
        return clientOrderId;
    }

    public Instrument instrument() {
        return instrument;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public long entrySequence() {
        return entrySequence;
    }

    public Instant enteredAt() {
        return enteredAt;
    }

    public long remainingQuantity() {
        return remainingQuantity;
    }

    void fill(long quantity) {
        if (quantity <= 0 || quantity > remainingQuantity) {
            throw new IllegalArgumentException("fill quantity must be positive and not exceed remaining quantity");
        }
        remainingQuantity -= quantity;
    }

    boolean isFilled() {
        return remainingQuantity == 0;
    }
}
