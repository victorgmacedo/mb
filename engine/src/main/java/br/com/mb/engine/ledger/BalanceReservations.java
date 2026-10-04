package br.com.mb.engine.ledger;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.book.PlacementPlan;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Side;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.BalanceChange;
import br.com.mb.ledger.domain.Ledger;
import java.util.ArrayList;
import java.util.List;

public final class BalanceReservations {
    private final InstrumentCatalog instruments;
    private final Ledger ledger;

    public BalanceReservations(InstrumentCatalog instruments, Ledger ledger) {
        this.instruments = instruments;
        this.ledger = ledger;
    }

    public void settle(PlacementPlan plan) {
        var order = plan.order();
        var listing = instruments.findListing(order.instrument()).orElseThrow();
        var changes = new ArrayList<BalanceChange>();
        var reservedAsset = order.side() == Side.BUY ? listing.quoteAsset() : listing.baseAsset();
        var reservedAmount = order.side() == Side.BUY ? notional(order.price(), order.quantity()) : order.quantity();
        var taker = new AccountId(order.accountId().value());
        changes.add(new BalanceChange(taker, reservedAsset, -reservedAmount, reservedAmount));
        for (var trade : plan.trades()) {
            var maker = new AccountId(trade.makerAccountId().value());
            var seller = trade.makerSide() == Side.SELL ? maker : taker;
            var buyer = trade.makerSide() == Side.SELL ? taker : maker;
            var quote = notional(trade.price(), trade.quantity());
            changes.add(new BalanceChange(seller, listing.baseAsset(), 0, -trade.quantity()));
            changes.add(new BalanceChange(buyer, listing.quoteAsset(), 0, -quote));
            changes.add(new BalanceChange(buyer, listing.baseAsset(), trade.quantity(), 0));
            changes.add(new BalanceChange(seller, listing.quoteAsset(), quote, 0));
            if (order.side() == Side.BUY && order.price() > trade.price()) {
                var improvement = notional(order.price() - trade.price(), trade.quantity());
                changes.add(new BalanceChange(taker, listing.quoteAsset(), improvement, -improvement));
            }
        }
        ledger.apply(changes);
    }

    public void release(BookOrder order) {
        var listing = instruments.findListing(order.instrument()).orElseThrow();
        var asset = order.side() == Side.BUY ? listing.quoteAsset() : listing.baseAsset();
        var amount = order.side() == Side.BUY ? notional(order.price(), order.remainingQuantity()) : order.remainingQuantity();
        ledger.apply(List.of(new BalanceChange(new AccountId(order.accountId().value()), asset, amount, -amount)));
    }

    private static long notional(long price, long quantity) {
        try { return Math.multiplyExact(price, quantity); }
        catch (ArithmeticException exception) { throw new InvalidOrderException("reserve amount overflow"); }
    }
}
