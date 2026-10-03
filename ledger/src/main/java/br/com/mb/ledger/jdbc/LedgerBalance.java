package br.com.mb.ledger.jdbc;

import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.LedgerException;

final class LedgerBalance {

    private String accountId;

    private String assetSymbol;

    private long available;

    private long locked;

    LedgerBalance(String accountId, String assetSymbol) {
        this.accountId = accountId;
        this.assetSymbol = assetSymbol;
    }

    LedgerBalance(String accountId, String assetSymbol, long available, long locked) {
        this(accountId, assetSymbol);
        this.available = available;
        this.locked = locked;
    }

    AssetBalance toBalance() {
        return new AssetBalance(available, locked);
    }

    void credit(long amount) {
        requirePositive(amount);
        available = checkedAdd(available, amount);
    }

    void debitAvailable(long amount) {
        requirePositive(amount);
        if (available < amount) {
            throw new LedgerException("insufficient available balance");
        }
        available -= amount;
    }

    void reserve(long amount) {
        requirePositive(amount);
        if (available < amount) {
            throw new LedgerException("insufficient available balance");
        }
        available -= amount;
        locked = checkedAdd(locked, amount);
    }

    void release(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new LedgerException("insufficient locked balance");
        }
        available = checkedAdd(available, amount);
        locked -= amount;
    }

    void debitLocked(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new LedgerException("insufficient locked balance");
        }
        locked -= amount;
    }

    void requireLocked(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new LedgerException("insufficient locked balance");
        }
    }

    void requireCanCredit(long amount) {
        requirePositive(amount);
        checkedAdd(available, amount);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new LedgerException("amount must be positive");
        }
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new LedgerException("balance overflow");
        }
    }
}
