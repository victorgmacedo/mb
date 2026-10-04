package br.com.mb.ledger.memory;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.BalanceChange;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.model.Asset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryLedgerTest {
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final AccountId account = new AccountId("A");
    private final Asset brl = new Asset("BRL");

    @Test
    void reservesAndReleasesWithoutChangingTotal() {
        assertEquals(AssetBalance.zero(), ledger.balanceOf(account, brl));
        ledger.credit(account, brl, 100);
        ledger.apply(List.of(new BalanceChange(account, brl, -60, 60)));
        assertEquals(new AssetBalance(40, 60), ledger.balanceOf(account, brl));
        assertThrows(LedgerException.class, () -> ledger.debitAvailable(account, brl, 41));
        ledger.apply(List.of(new BalanceChange(account, brl, 60, -60)));
        assertEquals(new AssetBalance(100, 0), ledger.balanceOf(account, brl));
    }

    @Test
    void failsTheEntireBatchWhenItsLastBalanceHasInsufficientFunds() {
        ledger.credit(account, brl, 100);
        assertThrows(LedgerException.class, () -> ledger.apply(List.of(
            new BalanceChange(account, brl, -50, 50),
            new BalanceChange(new AccountId("B"), brl, -1, 0))));
        assertEquals(new AssetBalance(100, 0), ledger.balanceOf(account, brl));
    }

    @Test
    void failsTheEntireBatchOnCreditOverflowIncludingLockedFunds() {
        ledger.credit(account, brl, Long.MAX_VALUE);
        ledger.apply(List.of(new BalanceChange(account, brl, -100, 100)));
        assertThrows(LedgerException.class, () -> ledger.credit(account, brl, 1));
        assertEquals(new AssetBalance(Long.MAX_VALUE - 100, 100), ledger.balanceOf(account, brl));
        assertThrows(LedgerException.class, () -> ledger.credit(account, brl, 0));
        assertThrows(LedgerException.class, () -> ledger.debitAvailable(account, brl, -1));
    }

    @Test
    void concurrentDebitsCannotOverspend() throws Exception {
        ledger.credit(account, brl, 100);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = workers.invokeAll(IntStream.range(0, 200).mapToObj(i -> (Callable<Boolean>) () -> {
                try { ledger.debitAvailable(account, brl, 1); return true; }
                catch (LedgerException exception) { return false; }
            }).toList());
            var accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertEquals(100, accepted);
        }
        assertEquals(AssetBalance.zero(), ledger.balanceOf(account, brl));
    }
}
