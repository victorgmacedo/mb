package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;

public interface Ledger {

    void credit(AccountId accountId, Asset asset, long amount);

    boolean creditFunding(String clientOrderId, AccountId accountId, Asset asset, long amount);

    void debitAvailable(AccountId accountId, Asset asset, long amount);

    boolean debitFunding(String clientOrderId, AccountId accountId, Asset asset, long amount);

    void reserve(AccountId accountId, Asset asset, long amount);

    void release(AccountId accountId, Asset asset, long amount);

    void settle(TradeSettlementInstruction instruction);

    boolean settleExecution(String executionId, TradeSettlementInstruction instruction);

    boolean releaseExecution(String executionId, AccountId accountId, Asset asset, long amount);

    AssetBalance balanceOf(AccountId accountId, Asset asset);
}
