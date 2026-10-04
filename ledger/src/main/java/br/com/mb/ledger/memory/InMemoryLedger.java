package br.com.mb.ledger.memory;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.BalanceChange;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.model.Asset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryLedger implements Ledger {
    private final Map<BalanceKey, AssetBalance> balances = new HashMap<>();

    @Override
    public synchronized void credit(AccountId accountId, Asset asset, long amount) {
        requirePositive(amount);
        apply(List.of(new BalanceChange(accountId, asset, amount, 0)));
    }

    @Override
    public synchronized void debitAvailable(AccountId accountId, Asset asset, long amount) {
        requirePositive(amount);
        apply(List.of(new BalanceChange(accountId, asset, -amount, 0)));
    }

    @Override
    public synchronized void apply(List<BalanceChange> changes) {
        // Only balances touched by this command are staged, never the entire ledger.
        var updated = new HashMap<BalanceKey, AssetBalance>();
        for (var change : changes) {
            var key = new BalanceKey(change.accountId(), change.asset());
            var current = updated.getOrDefault(key, balances.getOrDefault(key, AssetBalance.zero()));
            updated.put(key, changed(current, change));
        }
        balances.putAll(updated);
    }

    @Override
    public synchronized AssetBalance balanceOf(AccountId accountId, Asset asset) {
        return balances.getOrDefault(new BalanceKey(accountId, asset), AssetBalance.zero());
    }

    private static AssetBalance changed(AssetBalance current, BalanceChange change) {
        try {
            var available = Math.addExact(current.available(), change.availableDelta());
            var locked = Math.addExact(current.locked(), change.lockedDelta());
            if (available < 0) throw new LedgerException("insufficient available balance");
            if (locked < 0) throw new LedgerException("insufficient locked balance");
            Math.addExact(available, locked);
            return new AssetBalance(available, locked);
        } catch (ArithmeticException exception) {
            throw new LedgerException("balance overflow");
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new LedgerException("amount must be positive");
    }

    private record BalanceKey(AccountId accountId, Asset asset) {}
}
