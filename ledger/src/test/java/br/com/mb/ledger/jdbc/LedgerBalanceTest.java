package br.com.mb.ledger.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.LedgerException;
import org.junit.jupiter.api.Test;

class LedgerBalanceTest {

    @Test
    void creditIncreasesAvailableBalance() {
        var balance = new LedgerBalance("account-A", "BRL");

        balance.credit(1_000);

        assertEquals(new AssetBalance(1_000, 0), balance.toBalance());
    }

    @Test
    void reserveMovesAvailableToLockedBalance() {
        var balance = new LedgerBalance("account-A", "BRL");

        balance.credit(1_000);
        balance.reserve(400);

        assertEquals(new AssetBalance(600, 400), balance.toBalance());
    }

    @Test
    void reserveRejectsInsufficientAvailableBalance() {
        var balance = new LedgerBalance("account-A", "BRL");

        var exception = assertThrows(LedgerException.class, () -> balance.reserve(1));

        assertEquals("insufficient available balance", exception.getMessage());
    }

    @Test
    void releaseMovesLockedToAvailableBalance() {
        var balance = new LedgerBalance("account-A", "BRL");

        balance.credit(1_000);
        balance.reserve(400);
        balance.release(150);

        assertEquals(new AssetBalance(750, 250), balance.toBalance());
    }

    @Test
    void creditRejectsBalanceOverflow() {
        var balance = new LedgerBalance("account-A", "BRL");

        balance.credit(Long.MAX_VALUE);
        var exception = assertThrows(LedgerException.class, () -> balance.credit(1));

        assertEquals("balance overflow", exception.getMessage());
        assertEquals(new AssetBalance(Long.MAX_VALUE, 0), balance.toBalance());
    }
}
