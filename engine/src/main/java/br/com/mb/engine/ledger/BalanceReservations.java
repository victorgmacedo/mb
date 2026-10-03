package br.com.mb.engine.ledger;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.Trade;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.ListedInstrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import java.util.Objects;

public final class BalanceReservations {

    private final InstrumentCatalog instrumentCatalog;
    private final Ledger ledger;

    public BalanceReservations(InstrumentCatalog instrumentCatalog, Ledger ledger) {
        this.instrumentCatalog = Objects.requireNonNull(instrumentCatalog, "instrumentCatalog must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    }

    public void reserve(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        var listing = listing(order.instrument());
        try {
            switch (order.side()) {
                case BUY -> ledger.reserve(account(order.accountId().value()), listing.quoteAsset(), notional(order.price(), order.quantity()));
                case SELL -> ledger.reserve(account(order.accountId().value()), listing.baseAsset(), order.quantity());
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
                case BUY -> ledger.release(account(order.accountId().value()), listing.quoteAsset(), notional(order.price(), order.remainingQuantity()));
                case SELL -> ledger.release(account(order.accountId().value()), listing.baseAsset(), order.remainingQuantity());
            }
        } catch (LedgerException exception) {
            throw new InvalidOrderException(exception.getMessage());
        }
    }

    public void settle(Order takerOrder, PlacementResult placement) {
        Objects.requireNonNull(takerOrder, "takerOrder must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
        var listing = listing(takerOrder.instrument());
        try {
            for (var trade : placement.trades()) {
                ledger.settle(new TradeSettlementInstruction(
                    account(trade.makerAccountId().value()),
                    account(trade.takerAccountId().value()),
                    settlementSide(trade.makerSide()),
                    listing.baseAsset(),
                    listing.quoteAsset(),
                    trade.price(),
                    trade.quantity()
                ));
                releaseTakerPriceImprovement(takerOrder, listing, trade);
            }
        } catch (LedgerException exception) {
            throw new InvalidOrderException(exception.getMessage());
        }
    }

    private ListedInstrument listing(Instrument instrument) {
        return instrumentCatalog.findListing(instrument)
            .orElseThrow(() -> new InvalidOrderException("unknown instrument: " + instrument.symbol()));
    }

    private static AccountId account(String accountId) {
        return new AccountId(accountId);
    }

    private void releaseTakerPriceImprovement(Order takerOrder, ListedInstrument listing, Trade trade) {
        if (takerOrder.side() != Side.BUY || takerOrder.price() == trade.price()) {
            return;
        }
        ledger.release(
            account(takerOrder.accountId().value()),
            listing.quoteAsset(),
            notional(takerOrder.price() - trade.price(), trade.quantity())
        );
    }

    private static SettlementSide settlementSide(Side side) {
        return switch (side) {
            case BUY -> SettlementSide.BUY;
            case SELL -> SettlementSide.SELL;
        };
    }

    private static long notional(long price, long quantity) {
        try {
            return Math.multiplyExact(price, quantity);
        } catch (ArithmeticException exception) {
            throw new InvalidOrderException("reserve amount overflow");
        }
    }
}
