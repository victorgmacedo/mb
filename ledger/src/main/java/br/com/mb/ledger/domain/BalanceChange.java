package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;
import java.util.Objects;

/** Signed bucket changes; a batch is validated completely before any balance is replaced. */
public record BalanceChange(AccountId accountId, Asset asset, long availableDelta, long lockedDelta) {
    public BalanceChange {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(asset);
    }
}
