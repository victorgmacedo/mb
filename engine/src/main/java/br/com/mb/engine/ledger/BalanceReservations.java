package br.com.mb.engine.ledger;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.ListedInstrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.ledger.domain.InMemoryLedger;
import br.com.mb.ledger.domain.LedgerException;
import java.util.Objects;

public final class BalanceReservations {

    private final InstrumentCatalog instrumentCatalog;
    private final InMemoryLedger ledger;

    public BalanceReservations(InstrumentCatalog instrumentCatalog, InMemoryLedger ledger) {
        this.instrumentCatalog = Objects.requireNonNull(instrumentCatalog, "instrumentCatalog must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    }

    public void reserve(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        var listing = listing(order.instrument());
        try {
            switch (order.side()) {
                case BUY -> ledger.reserve(account(order.accountId()), listing.quoteAsset(), notional(order.price(), order.quantity()));
                case SELL -> ledger.reserve(account(order.accountId()), listing.baseAsset(), order.quantity());
            }
        } catch (LedgerException exception) {
            throw new InvalidOrderException(exception.getMessage());
        }
    }

    public void release(BookOrder order) {
        Objects.requireNonNull(order, "order must not be null");
        var listing = listing(order.instrument());
        try {
            switch (order.side()) {
                case BUY -> ledger.release(account(order.accountId()), listing.quoteAsset(), notional(order.price(), order.remainingQuantity()));
                case SELL -> ledger.release(account(order.accountId()), listing.baseAsset(), order.remainingQuantity());
            }
        } catch (LedgerException exception) {
            throw new InvalidOrderException(exception.getMessage());
        }
    }

    private ListedInstrument listing(Instrument instrument) {
        return instrumentCatalog.findListing(instrument)
            .orElseThrow(() -> new InvalidOrderException("unknown instrument: " + instrument.symbol()));
    }

    private static br.com.mb.ledger.domain.AccountId account(AccountId accountId) {
        return new br.com.mb.ledger.domain.AccountId(accountId.value());
    }

    private static long notional(long price, long quantity) {
        try {
            return Math.multiplyExact(price, quantity);
        } catch (ArithmeticException exception) {
            throw new InvalidOrderException("reserve amount overflow");
        }
    }
}
