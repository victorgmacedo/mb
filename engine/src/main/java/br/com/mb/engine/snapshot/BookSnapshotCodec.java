package br.com.mb.engine.snapshot;

import br.com.mb.engine.book.BookOrderView;
import br.com.mb.engine.book.BookPriceLevelView;
import br.com.mb.engine.book.OrderBook;
import br.com.mb.engine.snapshot.proto.BookSnapshotProto;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Instant;
import java.util.List;

public final class BookSnapshotCodec {

    public static final int SCHEMA_VERSION = 1;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public byte[] encode(OrderBook book, long lastEntrySequence) {
        return encode(snapshotFrom(book, lastEntrySequence));
    }

    public byte[] encode(BookSnapshot snapshot) {
        return toProto(snapshot).toByteArray();
    }

    public BookSnapshot decode(byte[] payload) {
        try {
            return fromProto(BookSnapshotProto.BookSnapshot.parseFrom(payload));
        } catch (InvalidProtocolBufferException exception) {
            throw new InvalidBookSnapshotException("invalid book snapshot protobuf payload", exception);
        }
    }

    public BookSnapshot snapshotFrom(OrderBook book, long lastEntrySequence) {
        return new BookSnapshot(
            book.instrument().symbol(),
            snapshotLevels(book.bidLevels()),
            snapshotLevels(book.askLevels()),
            lastEntrySequence,
            SCHEMA_VERSION
        );
    }

    private BookSnapshotProto.BookSnapshot toProto(BookSnapshot snapshot) {
        return BookSnapshotProto.BookSnapshot.newBuilder()
            .setInstrument(snapshot.instrument())
            .addAllBids(protoLevels(snapshot.bids()))
            .addAllAsks(protoLevels(snapshot.asks()))
            .setLastEntrySequence(snapshot.lastEntrySequence())
            .setSchemaVersion(snapshot.schemaVersion())
            .build();
    }

    private BookSnapshot fromProto(BookSnapshotProto.BookSnapshot snapshot) {
        return new BookSnapshot(
            snapshot.getInstrument(),
            snapshotLevelsFromProto(snapshot.getBidsList()),
            snapshotLevelsFromProto(snapshot.getAsksList()),
            snapshot.getLastEntrySequence(),
            snapshot.getSchemaVersion()
        );
    }

    private List<BookSnapshotPriceLevel> snapshotLevels(List<BookPriceLevelView> levels) {
        return levels.stream()
            .map(level -> new BookSnapshotPriceLevel(
                level.price(),
                level.orders().stream()
                    .map(this::snapshotOrder)
                    .toList()
            ))
            .toList();
    }

    private BookSnapshotOrder snapshotOrder(BookOrderView order) {
        return new BookSnapshotOrder(
            order.accountId(),
            order.clientOrderId(),
            order.remainingQuantity(),
            order.entrySequence(),
            order.enteredAt()
        );
    }

    private List<BookSnapshotProto.PriceLevel> protoLevels(List<BookSnapshotPriceLevel> levels) {
        return levels.stream()
            .map(this::protoLevel)
            .toList();
    }

    private BookSnapshotProto.PriceLevel protoLevel(BookSnapshotPriceLevel level) {
        return BookSnapshotProto.PriceLevel.newBuilder()
            .setPrice(level.price())
            .addAllOrders(level.orders().stream()
                .map(this::protoOrder)
                .toList())
            .build();
    }

    private BookSnapshotProto.OpenOrder protoOrder(BookSnapshotOrder order) {
        return BookSnapshotProto.OpenOrder.newBuilder()
            .setAccountId(order.accountId())
            .setClientOrderId(order.clientOrderId())
            .setRemainingQuantity(order.remainingQuantity())
            .setEntrySequence(order.entrySequence())
            .setEnteredAtEpochNanos(toEpochNanos(order.enteredAt()))
            .build();
    }

    private List<BookSnapshotPriceLevel> snapshotLevelsFromProto(List<BookSnapshotProto.PriceLevel> levels) {
        return levels.stream()
            .map(this::snapshotLevelFromProto)
            .toList();
    }

    private BookSnapshotPriceLevel snapshotLevelFromProto(BookSnapshotProto.PriceLevel level) {
        return new BookSnapshotPriceLevel(
            level.getPrice(),
            level.getOrdersList().stream()
                .map(this::snapshotOrderFromProto)
                .toList()
        );
    }

    private BookSnapshotOrder snapshotOrderFromProto(BookSnapshotProto.OpenOrder order) {
        return new BookSnapshotOrder(
            order.getAccountId(),
            order.getClientOrderId(),
            order.getRemainingQuantity(),
            order.getEntrySequence(),
            fromEpochNanos(order.getEnteredAtEpochNanos())
        );
    }

    private static long toEpochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    private static Instant fromEpochNanos(long epochNanos) {
        return Instant.ofEpochSecond(epochNanos / NANOS_PER_SECOND, epochNanos % NANOS_PER_SECOND);
    }
}
