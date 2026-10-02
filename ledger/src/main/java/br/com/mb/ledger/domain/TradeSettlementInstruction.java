package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;
import java.util.Objects;

public record TradeSettlementInstruction(
    AccountId makerAccountId,
    AccountId takerAccountId,
    SettlementSide makerSide,
    Asset baseAsset,
    Asset quoteAsset,
    long price,
    long quantity
) {

    public TradeSettlementInstruction {
        Objects.requireNonNull(makerAccountId, "makerAccountId must not be null");
        Objects.requireNonNull(takerAccountId, "takerAccountId must not be null");
        Objects.requireNonNull(makerSide, "makerSide must not be null");
        Objects.requireNonNull(baseAsset, "baseAsset must not be null");
        Objects.requireNonNull(quoteAsset, "quoteAsset must not be null");
        if (baseAsset.equals(quoteAsset)) {
            throw new LedgerException("base and quote assets must be different");
        }
        if (price <= 0) {
            throw new LedgerException("price must be positive");
        }
        if (quantity <= 0) {
            throw new LedgerException("quantity must be positive");
        }
    }

    public long quoteAmount() {
        try {
            return Math.multiplyExact(price, quantity);
        } catch (ArithmeticException exception) {
            throw new LedgerException("quote amount overflow");
        }
    }
}
