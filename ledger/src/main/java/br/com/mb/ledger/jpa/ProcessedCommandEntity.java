package br.com.mb.ledger.jpa;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.model.Asset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;

@Entity
@Table(
    name = "processed_commands",
    uniqueConstraints = @UniqueConstraint(name = "uk_processed_command_type_client_order", columnNames = {"command_type", "client_order_id"})
)
public class ProcessedCommandEntity {

    static final String FUNDING_CREDIT = "FUNDING_CREDIT";
    static final String TRADE_SETTLEMENT = "TRADE_SETTLEMENT";
    static final String BALANCE_RELEASE = "BALANCE_RELEASE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_type", nullable = false, length = 64)
    private String commandType;

    @Column(name = "client_order_id", nullable = false, length = 128)
    private String clientOrderId;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Column(name = "asset_symbol", nullable = false, length = 32)
    private String assetSymbol;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    protected ProcessedCommandEntity() {
    }

    ProcessedCommandEntity(String commandType, String clientOrderId, String accountId, String assetSymbol, long amount) {
        this(commandType, clientOrderId, accountId, assetSymbol, amount, null);
    }

    ProcessedCommandEntity(String commandType, String clientOrderId, String accountId, String assetSymbol, long amount, String payloadHash) {
        this.commandType = Objects.requireNonNull(commandType, "commandType must not be null");
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.assetSymbol = Objects.requireNonNull(assetSymbol, "assetSymbol must not be null");
        if (amount <= 0) {
            throw new LedgerException("amount must be positive");
        }
        this.amount = amount;
        this.payloadHash = payloadHash;
    }

    boolean matchesFundingCredit(AccountId accountId, Asset asset, long amount) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        return FUNDING_CREDIT.equals(commandType)
            && this.accountId.equals(accountId.value())
            && assetSymbol.equals(asset.symbol())
            && this.amount == amount;
    }

    boolean matchesPayloadHash(String payloadHash) {
        return Objects.equals(this.payloadHash, payloadHash);
    }
}
