package br.com.mb.engine.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void buyMatchesBestAskAtMakerPrice() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("ask-1", Side.SELL, 100, 10));

        var result = book.place(order("bid-1", Side.BUY, 110, 10));

        assertEquals(1, result.trades().size());
        assertEquals(new ClientOrderId("ask-1"), result.trades().getFirst().makerClientOrderId());
        assertEquals(new ClientOrderId("bid-1"), result.trades().getFirst().takerClientOrderId());
        assertEquals(100, result.trades().getFirst().price());
        assertEquals(10, result.trades().getFirst().quantity());
        assertTrue(result.fullyFilled());
        assertFalse(book.bestAsk().isPresent());
        assertFalse(book.bestBid().isPresent());
    }

    @Test
    void sellMatchesBestBidAtMakerPrice() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("bid-1", Side.BUY, 100, 10));

        var result = book.place(order("ask-1", Side.SELL, 90, 10));

        assertEquals(1, result.trades().size());
        assertEquals(100, result.trades().getFirst().price());
        assertTrue(result.fullyFilled());
        assertFalse(book.bestBid().isPresent());
        assertFalse(book.bestAsk().isPresent());
    }

    @Test
    void partialMatchLeavesMakerInBook() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("ask-1", Side.SELL, 100, 10));

        var result = book.place(order("bid-1", Side.BUY, 100, 4));

        assertEquals(1, result.trades().size());
        assertEquals(6, result.trades().getFirst().makerLeavesQuantity());
        assertEquals(0, result.trades().getFirst().takerLeavesQuantity());
        assertEquals(new ClientOrderId("ask-1"), book.bestAsk().orElseThrow().clientOrderId());
        assertEquals(6, book.bestAsk().orElseThrow().remainingQuantity());
    }

    @Test
    void partialMatchRestsTakerRemainder() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("ask-1", Side.SELL, 100, 4));

        var result = book.place(order("bid-1", Side.BUY, 100, 10));

        assertEquals(1, result.trades().size());
        assertEquals(0, result.trades().getFirst().makerLeavesQuantity());
        assertEquals(6, result.trades().getFirst().takerLeavesQuantity());
        assertTrue(result.partiallyFilled());
        assertEquals(new ClientOrderId("bid-1"), book.bestBid().orElseThrow().clientOrderId());
        assertEquals(6, book.bestBid().orElseThrow().remainingQuantity());
    }

    @Test
    void crossesMultiplePriceLevelsAndKeepsBookUncrossed() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("ask-1", Side.SELL, 100, 4));
        book.add(order("ask-2", Side.SELL, 101, 4));
        book.add(order("ask-3", Side.SELL, 102, 4));

        var result = book.place(order("bid-1", Side.BUY, 101, 10));

        assertEquals(2, result.trades().size());
        assertEquals(new ClientOrderId("ask-1"), result.trades().get(0).makerClientOrderId());
        assertEquals(new ClientOrderId("ask-2"), result.trades().get(1).makerClientOrderId());
        assertEquals(new ClientOrderId("ask-3"), book.bestAsk().orElseThrow().clientOrderId());
        assertEquals(new ClientOrderId("bid-1"), book.bestBid().orElseThrow().clientOrderId());
        assertTrue(book.bestBid().orElseThrow().price() < book.bestAsk().orElseThrow().price());
    }

    @Test
    void matchesFifoWithinSamePriceLevel() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("ask-1", Side.SELL, 100, 4));
        book.add(order("ask-2", Side.SELL, 100, 4));

        var result = book.place(order("bid-1", Side.BUY, 100, 8));

        assertEquals(new ClientOrderId("ask-1"), result.trades().get(0).makerClientOrderId());
        assertEquals(new ClientOrderId("ask-2"), result.trades().get(1).makerClientOrderId());
    }

    @Test
    void rejectsSelfTradeForBothSidesWithoutMutatingBook() {
        for (var side : Side.values()) {
            var book = new OrderBook(BTC_BRL);
            var opposite = side == Side.BUY ? Side.SELL : Side.BUY;
            var maker = order("maker", opposite, 100, 10);
            book.add(maker);
            var taker = new Order(maker.accountId(), new ClientOrderId("taker"), BTC_BRL,
                side, 100, 4, OrderStatus.ACCEPTED);

            assertThrows(InvalidOrderException.class, () -> book.place(taker));

            assertEquals(10, book.find(maker.clientOrderId()).orElseThrow().remainingQuantity());
            assertFalse(book.find(taker.clientOrderId()).isPresent());
        }
    }

    @Test
    void rejectsEntireOrderWhenSelfTradeWouldFollowExternalFill() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("external", Side.SELL, 99, 4));
        var own = new Order(new AccountId("buyer-A"), new ClientOrderId("own"), BTC_BRL,
            Side.SELL, 100, 10, OrderStatus.ACCEPTED);
        book.add(own);
        var before = book.openOrders();

        assertThrows(InvalidOrderException.class, () -> book.place(order("taker", Side.BUY, 100, 5)));

        assertEquals(before, book.openOrders());
    }

    @Test
    void allowsExternalFillThatFinishesBeforeOwnOrderAndNonCrossingOwnOrder() {
        var book = new OrderBook(BTC_BRL);
        book.add(order("external", Side.SELL, 100, 4));
        book.add(new Order(new AccountId("buyer-A"), new ClientOrderId("own"), BTC_BRL,
            Side.SELL, 100, 10, OrderStatus.ACCEPTED));

        assertTrue(book.place(order("taker", Side.BUY, 100, 4)).fullyFilled());
        assertTrue(book.place(order("non-crossing", Side.BUY, 99, 1)).restingOrder().isPresent());
        assertEquals(10, book.find(new ClientOrderId("own")).orElseThrow().remainingQuantity());
    }

    private static Order order(String clientOrderId, Side side, long price, long quantity) {
        return new Order(
            new AccountId(side == Side.BUY ? "buyer-A" : "seller-A"),
            new ClientOrderId(clientOrderId),
            BTC_BRL,
            side,
            price,
            quantity,
            OrderStatus.ACCEPTED
        );
    }
}
