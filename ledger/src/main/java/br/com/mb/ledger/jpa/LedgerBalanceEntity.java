package br.com.mb.ledger.jpa;

import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.LedgerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
    name = "ledger_balances",
    uniqueConstraints = @UniqueConstraint(name = "uk_ledger_balance_account_asset", columnNames = {"account_id", "asset_symbol"})
)
public class LedgerBalanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Column(name = "asset_symbol", nullable = false, length = 32)
    private String assetSymbol;

    @Column(name = "available", nullable = false)
    private long available;

    @Column(name = "locked", nullable = false)
    private long locked;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LedgerBalanceEntity() {
    }

    LedgerBalanceEntity(String accountId, String assetSymbol) {
        this.accountId = accountId;
        this.assetSymbol = assetSymbol;
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
