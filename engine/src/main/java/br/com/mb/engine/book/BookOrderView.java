package br.com.mb.engine.book;

import br.com.mb.engine.domain.Side;
import java.time.Instant;

public record BookOrderView(
    String accountId,
    String clientOrderId,
    String instrument,
    Side side,
    long price,
    long entrySequence,
    Instant enteredAt,
    long remainingQuantity
) {

    static BookOrderView from(BookOrder order) {
        return new BookOrderView(
            order.accountId().value(),
            order.clientOrderId().value(),
            order.instrument().symbol(),
            order.side(),
            order.price(),
            order.entrySequence(),
            order.enteredAt(),
            order.remainingQuantity()
        );
    }
}
