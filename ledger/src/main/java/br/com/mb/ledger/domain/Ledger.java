package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;
import java.util.List;

public interface Ledger {
    void credit(AccountId accountId, Asset asset, long amount);
    void debitAvailable(AccountId accountId, Asset asset, long amount);
    void apply(List<BalanceChange> changes);
    AssetBalance balanceOf(AccountId accountId, Asset asset);
}
