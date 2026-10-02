package br.com.mb.engine.snapshot;

import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderStatus;
import br.com.mb.engine.domain.Side;
import java.util.HashSet;
import java.util.List;

public final class BookSnapshotRestorer {

    public EngineState restore(List<BookSnapshot> snapshots) {
        var state = new EngineState();
        var instruments = new HashSet<String>();
        var sequences = new HashSet<Long>();
        Long lastSequence = null;
        try {
            for (var snapshot : snapshots) {
                if (snapshot.schemaVersion() != BookSnapshotCodec.SCHEMA_VERSION
                    || !instruments.add(snapshot.instrument())
                    || (lastSequence != null && lastSequence != snapshot.lastEntrySequence())) {
                    throw new InvalidBookSnapshotException("Inconsistent snapshot schema, instrument or sequence");
                }
                lastSequence = snapshot.lastEntrySequence();
                state.restoreEntrySequence(lastSequence);
                var instrument = InstrumentCatalog.defaultCatalog().findBySymbol(snapshot.instrument())
                    .orElseThrow(() -> new InvalidBookSnapshotException("Unknown snapshot instrument"));
                state.book(instrument);
                restoreLevels(state, snapshot, Side.BUY, snapshot.bids(), sequences);
                restoreLevels(state, snapshot, Side.SELL, snapshot.asks(), sequences);
            }
            return state;
        } catch (IllegalArgumentException | InvalidOrderException exception) {
            throw new InvalidBookSnapshotException("Invalid snapshot order", exception);
        }
    }

    private void restoreLevels(EngineState state, BookSnapshot snapshot, Side side,
                               List<BookSnapshotPriceLevel> levels, HashSet<Long> sequences) {
        Long previousPrice = null;
        var instrument = InstrumentCatalog.defaultCatalog().findBySymbol(snapshot.instrument()).orElseThrow();
        for (var level : levels) {
            if (level.orders().isEmpty() || level.price() <= 0 || (previousPrice != null
                && (side == Side.BUY ? level.price() >= previousPrice : level.price() <= previousPrice))) {
                throw new InvalidBookSnapshotException("Invalid snapshot price levels");
            }
            previousPrice = level.price();
            long previousSequence = 0;
            for (var order : level.orders()) {
                if (order.entrySequence() <= previousSequence || order.entrySequence() > snapshot.lastEntrySequence()
                    || !sequences.add(order.entrySequence()) || order.enteredAt() == null
                    || state.hasOpenOrder(new ClientOrderId(order.clientOrderId()))) {
                    throw new InvalidBookSnapshotException("Invalid snapshot order priority or identity");
                }
                previousSequence = order.entrySequence();
                var placement = state.place(new Order(new AccountId(order.accountId()),
                    new ClientOrderId(order.clientOrderId()), instrument, side, level.price(),
                    order.remainingQuantity(), OrderStatus.ACCEPTED), order.entrySequence(), order.enteredAt());
                if (!placement.trades().isEmpty()) {
                    throw new InvalidBookSnapshotException("Snapshot contains a crossed book");
                }
            }
        }
    }
}
