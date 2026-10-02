package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;

public interface Ledger {

    void credit(AccountId accountId, Asset asset, long amount);

    void debitAvailable(AccountId accountId, Asset asset, long amount);

    void reserve(AccountId accountId, Asset asset, long amount);

    void release(AccountId accountId, Asset asset, long amount);

    void settle(TradeSettlementInstruction instruction);

    AssetBalance balanceOf(AccountId accountId, Asset asset);
}
