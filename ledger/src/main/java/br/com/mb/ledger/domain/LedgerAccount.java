package br.com.mb.ledger.domain;

import br.com.mb.shared.model.Asset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class LedgerAccount {

    private final AccountId accountId;
    private final Map<Asset, AssetBalance> balances = new HashMap<>();

    public LedgerAccount(AccountId accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    public AccountId accountId() {
        return accountId;
    }

    public AssetBalance balanceOf(Asset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        return balances.getOrDefault(asset, AssetBalance.zero());
    }

    public Map<Asset, AssetBalance> balances() {
        return Map.copyOf(balances);
    }

    void credit(Asset asset, long amount) {
        requirePositive(amount);
        update(asset, balanceOf(asset).credit(amount));
    }

    void debitAvailable(Asset asset, long amount) {
        requirePositive(amount);
        update(asset, balanceOf(asset).debitAvailable(amount));
    }

    void reserve(Asset asset, long amount) {
        requirePositive(amount);
        update(asset, balanceOf(asset).reserve(amount));
    }

    void release(Asset asset, long amount) {
        requirePositive(amount);
        update(asset, balanceOf(asset).release(amount));
    }

    void debitLocked(Asset asset, long amount) {
        requirePositive(amount);
        update(asset, balanceOf(asset).debitLocked(amount));
    }

    void requireLocked(Asset asset, long amount) {
        requirePositive(amount);
        if (balanceOf(asset).locked() < amount) {
            throw new LedgerException("insufficient locked balance");
        }
    }

    void requireCanCredit(Asset asset, long amount) {
        requirePositive(amount);
        balanceOf(asset).requireCanCredit(amount);
    }

    private void update(Asset asset, AssetBalance balance) {
        Objects.requireNonNull(asset, "asset must not be null");
        balances.put(asset, balance);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new LedgerException("amount must be positive");
        }
    }
}
