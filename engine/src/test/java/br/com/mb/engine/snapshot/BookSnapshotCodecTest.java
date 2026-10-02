package br.com.mb.engine.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.engine.book.OrderBook;
import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderStatus;
import br.com.mb.engine.domain.Side;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BookSnapshotCodecTest {

    private static final Instrument BTC_BRL = new Instrument("BTC/BRL");

    private final BookSnapshotCodec codec = new BookSnapshotCodec();

    @Test
    void encodesAndDecodesBookWithPriceTimePriority() {
        var book = new OrderBook(BTC_BRL);
        book.place(order("bid-1", Side.BUY, 100, 10), 1, Instant.parse("2026-10-02T10:00:00Z"));
        book.place(order("bid-2", Side.BUY, 105, 20), 2, Instant.parse("2026-10-02T10:00:01Z"));
        book.place(order("bid-3", Side.BUY, 105, 30), 3, Instant.parse("2026-10-02T10:00:02.123456789Z"));
        book.place(order("ask-1", Side.SELL, 110, 40), 4, Instant.parse("2026-10-02T10:00:03Z"));
        book.place(order("ask-2", Side.SELL, 115, 50), 5, Instant.parse("2026-10-02T10:00:04Z"));

        var payload = codec.encode(book, 5);
        var snapshot = codec.decode(payload);

        assertTrue(payload.length > 0);
        assertEquals("BTC/BRL", snapshot.instrument());
        assertEquals(5, snapshot.lastEntrySequence());
        assertEquals(BookSnapshotCodec.SCHEMA_VERSION, snapshot.schemaVersion());

        assertEquals(2, snapshot.bids().size());
        assertEquals(105, snapshot.bids().get(0).price());
        assertEquals(100, snapshot.bids().get(1).price());
        assertEquals("bid-2", snapshot.bids().get(0).orders().get(0).clientOrderId());
        assertEquals("bid-3", snapshot.bids().get(0).orders().get(1).clientOrderId());

        assertEquals(2, snapshot.asks().size());
        assertEquals(110, snapshot.asks().get(0).price());
        assertEquals(115, snapshot.asks().get(1).price());
        assertEquals("ask-1", snapshot.asks().get(0).orders().getFirst().clientOrderId());

        var fifoOrder = snapshot.bids().get(0).orders().get(1);
        assertEquals(30, fifoOrder.remainingQuantity());
        assertEquals(3, fifoOrder.entrySequence());
        assertEquals(Instant.parse("2026-10-02T10:00:02.123456789Z"), fifoOrder.enteredAt());
    }

    @Test
    void keepsBinaryPayloadStableAfterRoundTrip() {
        var book = new OrderBook(BTC_BRL);
        book.place(order("bid-1", Side.BUY, 100, 10), 1, Instant.parse("2026-10-02T10:00:00Z"));

        var firstPayload = codec.encode(book, 1);
        var secondPayload = codec.encode(codec.decode(firstPayload));

        assertArrayEquals(firstPayload, secondPayload);
    }

    @Test
    void rejectsInvalidPayload() {
        var exception = assertThrows(
            InvalidBookSnapshotException.class,
            () -> codec.decode(new byte[] {1, 2, 3})
        );

        assertEquals("invalid book snapshot protobuf payload", exception.getMessage());
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
