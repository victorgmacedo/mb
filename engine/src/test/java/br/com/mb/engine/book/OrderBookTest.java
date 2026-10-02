package br.com.mb.engine.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderStatus;
import br.com.mb.engine.domain.Side;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static final Instrument BTC_BRL = new Instrument("BTC/BRL");

    @Test
    void keepsHighestBidAsBestBid() {
        var book = new OrderBook(BTC_BRL);

        book.add(order("bid-1", Side.BUY, 100, 10));
        book.add(order("bid-2", Side.BUY, 110, 10));
        book.add(order("bid-3", Side.BUY, 90, 10));

        assertEquals(new ClientOrderId("bid-2"), book.bestBid().orElseThrow().clientOrderId());
    }

    @Test
    void keepsLowestAskAsBestAsk() {
        var book = new OrderBook(BTC_BRL);

        book.add(order("ask-1", Side.SELL, 100, 10));
        book.add(order("ask-2", Side.SELL, 90, 10));
        book.add(order("ask-3", Side.SELL, 110, 10));

        assertEquals(new ClientOrderId("ask-2"), book.bestAsk().orElseThrow().clientOrderId());
    }

    @Test
    void preservesFifoAtSamePrice() {
        var book = new OrderBook(BTC_BRL);

        book.add(order("bid-1", Side.BUY, 100, 10));
        book.add(order("bid-2", Side.BUY, 100, 10));

        assertEquals(new ClientOrderId("bid-1"), book.bestBid().orElseThrow().clientOrderId());
    }

    @Test
    void cancelRemovesOpenOrder() {
        var book = new OrderBook(BTC_BRL);
        var clientOrderId = new ClientOrderId("bid-1");

        book.add(order("bid-1", Side.BUY, 100, 10));
        book.cancel(clientOrderId);

        assertFalse(book.find(clientOrderId).isPresent());
        assertFalse(book.bestBid().isPresent());
    }

    @Test
    void cancelRejectsUnknownOrder() {
        var book = new OrderBook(BTC_BRL);

        var exception = assertThrows(
            InvalidOrderException.class,
            () -> book.cancel(new ClientOrderId("missing"))
        );

        assertEquals("open order not found: missing", exception.getMessage());
    }

    @Test
    void rejectsDuplicateClientOrderId() {
        var book = new OrderBook(BTC_BRL);

        book.add(order("bid-1", Side.BUY, 100, 10));

        var exception = assertThrows(InvalidOrderException.class, () -> book.add(order("bid-1", Side.BUY, 101, 10)));

        assertEquals("duplicate client order id: bid-1", exception.getMessage());
    }

    private static Order order(String clientOrderId, Side side, long price, long quantity) {
        return new Order(
            new AccountId("account-A"),
            new ClientOrderId(clientOrderId),
            BTC_BRL,
            side,
            price,
            quantity,
            OrderStatus.ACCEPTED
        );
    }
}
