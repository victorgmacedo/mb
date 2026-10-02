package br.com.mb.ledger.domain;

public record AssetBalance(long available, long locked) {

    public AssetBalance {
        if (available < 0) {
            throw new LedgerException("available balance must not be negative");
        }
        if (locked < 0) {
            throw new LedgerException("locked balance must not be negative");
        }
    }

    public static AssetBalance zero() {
        return new AssetBalance(0, 0);
    }

    AssetBalance credit(long amount) {
        return new AssetBalance(checkedAdd(available, amount), locked);
    }

    AssetBalance debitAvailable(long amount) {
        if (available < amount) {
            throw new LedgerException("insufficient available balance");
        }
        return new AssetBalance(available - amount, locked);
    }

    AssetBalance reserve(long amount) {
        if (available < amount) {
            throw new LedgerException("insufficient available balance");
        }
        return new AssetBalance(available - amount, checkedAdd(locked, amount));
    }

    AssetBalance release(long amount) {
        if (locked < amount) {
            throw new LedgerException("insufficient locked balance");
        }
        return new AssetBalance(checkedAdd(available, amount), locked - amount);
    }

    AssetBalance debitLocked(long amount) {
        if (locked < amount) {
            throw new LedgerException("insufficient locked balance");
        }
        return new AssetBalance(available, locked - amount);
    }

    void requireCanCredit(long amount) {
        checkedAdd(available, amount);
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new LedgerException("balance overflow");
        }
    }
}
