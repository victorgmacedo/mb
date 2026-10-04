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
}
