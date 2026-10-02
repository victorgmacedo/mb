package br.com.mb.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mb.shared.model.Asset;
import org.junit.jupiter.api.Test;

class InMemoryLedgerTest {

    private static final AccountId MAKER = new AccountId("maker");
    private static final AccountId TAKER = new AccountId("taker");
    private static final Asset BTC = new Asset("BTC");
    private static final Asset BRL = new Asset("BRL");

    @Test
    void creditIncreasesAvailableBalance() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BTC, 10);

        assertEquals(new AssetBalance(10, 0), ledger.account(MAKER).balanceOf(BTC));
    }

    @Test
    void reserveMovesAvailableToLockedBalance() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BRL, 1_000);
        ledger.reserve(MAKER, BRL, 400);

        assertEquals(new AssetBalance(600, 400), ledger.account(MAKER).balanceOf(BRL));
    }

    @Test
    void reserveRejectsInsufficientAvailableBalance() {
        var ledger = new InMemoryLedger();

        var exception = assertThrows(LedgerException.class, () -> ledger.reserve(MAKER, BRL, 1));

        assertEquals("insufficient available balance", exception.getMessage());
    }

    @Test
    void releaseMovesLockedToAvailableBalance() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BRL, 1_000);
        ledger.reserve(MAKER, BRL, 400);
        ledger.release(MAKER, BRL, 150);

        assertEquals(new AssetBalance(750, 250), ledger.account(MAKER).balanceOf(BRL));
    }

    @Test
    void settlesTradeWhenMakerIsSeller() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BTC, 5);
        ledger.credit(TAKER, BRL, 500);
        ledger.reserve(MAKER, BTC, 5);
        ledger.reserve(TAKER, BRL, 500);

        ledger.settle(new TradeSettlementInstruction(MAKER, TAKER, SettlementSide.SELL, BTC, BRL, 100, 5));

        assertEquals(new AssetBalance(0, 0), ledger.account(MAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(500, 0), ledger.account(MAKER).balanceOf(BRL));
        assertEquals(new AssetBalance(5, 0), ledger.account(TAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(0, 0), ledger.account(TAKER).balanceOf(BRL));
    }

    @Test
    void settlesTradeWhenMakerIsBuyer() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BRL, 500);
        ledger.credit(TAKER, BTC, 5);
        ledger.reserve(MAKER, BRL, 500);
        ledger.reserve(TAKER, BTC, 5);

        ledger.settle(new TradeSettlementInstruction(MAKER, TAKER, SettlementSide.BUY, BTC, BRL, 100, 5));

        assertEquals(new AssetBalance(5, 0), ledger.account(MAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(0, 0), ledger.account(MAKER).balanceOf(BRL));
        assertEquals(new AssetBalance(0, 0), ledger.account(TAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(500, 0), ledger.account(TAKER).balanceOf(BRL));
    }

    @Test
    void settlementDoesNotPartiallyMutateWhenLockedBalanceIsInsufficient() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BTC, 5);
        ledger.reserve(MAKER, BTC, 5);

        var instruction = new TradeSettlementInstruction(MAKER, TAKER, SettlementSide.SELL, BTC, BRL, 100, 5);
        var exception = assertThrows(LedgerException.class, () -> ledger.settle(instruction));

        assertEquals("insufficient locked balance", exception.getMessage());
        assertEquals(new AssetBalance(0, 5), ledger.account(MAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(0, 0), ledger.account(MAKER).balanceOf(BRL));
        assertEquals(new AssetBalance(0, 0), ledger.account(TAKER).balanceOf(BTC));
        assertEquals(new AssetBalance(0, 0), ledger.account(TAKER).balanceOf(BRL));
    }

    @Test
    void creditRejectsBalanceOverflow() {
        var ledger = new InMemoryLedger();

        ledger.credit(MAKER, BRL, Long.MAX_VALUE);
        var exception = assertThrows(LedgerException.class, () -> ledger.credit(MAKER, BRL, 1));

        assertEquals("balance overflow", exception.getMessage());
        assertEquals(new AssetBalance(Long.MAX_VALUE, 0), ledger.account(MAKER).balanceOf(BRL));
    }
}
