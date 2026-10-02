package br.com.mb.engine.book;

import br.com.mb.engine.domain.Side;

public record BookOrderView(
    String accountId,
    String clientOrderId,
    String instrument,
    Side side,
    long price,
    long entrySequence,
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
            order.remainingQuantity()
        );
    }
}
