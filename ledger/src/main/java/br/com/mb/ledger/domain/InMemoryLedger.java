package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class InMemoryLedger {

    private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();

    public synchronized void credit(AccountId accountId, Asset asset, long amount) {
        account(accountId).credit(asset, amount);
    }

    public synchronized void debitAvailable(AccountId accountId, Asset asset, long amount) {
        account(accountId).debitAvailable(asset, amount);
    }

    public synchronized void reserve(AccountId accountId, Asset asset, long amount) {
        account(accountId).reserve(asset, amount);
    }

    public synchronized void release(AccountId accountId, Asset asset, long amount) {
        account(accountId).release(asset, amount);
    }

    public synchronized void settle(TradeSettlementInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");

        var maker = account(instruction.makerAccountId());
        var taker = account(instruction.takerAccountId());
        var quoteAmount = instruction.quoteAmount();

        if (instruction.makerSide() == SettlementSide.SELL) {
            maker.requireLocked(instruction.baseAsset(), instruction.quantity());
            taker.requireLocked(instruction.quoteAsset(), quoteAmount);
            maker.requireCanCredit(instruction.quoteAsset(), quoteAmount);
            taker.requireCanCredit(instruction.baseAsset(), instruction.quantity());
            maker.debitLocked(instruction.baseAsset(), instruction.quantity());
            taker.debitLocked(instruction.quoteAsset(), quoteAmount);
            maker.credit(instruction.quoteAsset(), quoteAmount);
            taker.credit(instruction.baseAsset(), instruction.quantity());
            return;
        }

        maker.requireLocked(instruction.quoteAsset(), quoteAmount);
        taker.requireLocked(instruction.baseAsset(), instruction.quantity());
        maker.requireCanCredit(instruction.baseAsset(), instruction.quantity());
        taker.requireCanCredit(instruction.quoteAsset(), quoteAmount);
        maker.debitLocked(instruction.quoteAsset(), quoteAmount);
        taker.debitLocked(instruction.baseAsset(), instruction.quantity());
        maker.credit(instruction.baseAsset(), instruction.quantity());
        taker.credit(instruction.quoteAsset(), quoteAmount);
    }

    public synchronized LedgerAccount account(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return accounts.computeIfAbsent(accountId, LedgerAccount::new);
    }
}
