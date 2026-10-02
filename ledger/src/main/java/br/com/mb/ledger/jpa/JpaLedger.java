package br.com.mb.ledger.jpa;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.model.Asset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaLedger implements Ledger {

    private final LedgerBalanceRepository balances;
    private final ProcessedCommandRepository processedCommands;

    public JpaLedger(LedgerBalanceRepository balances, ProcessedCommandRepository processedCommands) {
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
        this.processedCommands = Objects.requireNonNull(processedCommands, "processedCommands must not be null");
    }

    @Override
    @Transactional
    public void credit(AccountId accountId, Asset asset, long amount) {
        balanceForUpdate(accountId, asset).credit(amount);
    }

    @Override
    @Transactional
    public boolean creditFunding(String clientOrderId, AccountId accountId, Asset asset, long amount) {
        Objects.requireNonNull(clientOrderId, "clientOrderId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        if (amount <= 0) {
            throw new LedgerException("amount must be positive");
        }

        var inserted = processedCommands.insertIfAbsent(
            ProcessedCommandEntity.FUNDING_CREDIT,
            clientOrderId,
            accountId.value(),
            asset.symbol(),
            amount,
            fundingPayloadHash(accountId, asset, amount)
        );
        if (inserted == 1) {
            balanceForUpdate(accountId, asset).credit(amount);
            return true;
        }

        var existing = processedCommands.findByCommandTypeAndClientOrderId(ProcessedCommandEntity.FUNDING_CREDIT, clientOrderId)
            .orElseThrow(() -> new LedgerException("processed funding command not found after conflict"));
        if (!existing.matchesFundingCredit(accountId, asset, amount)) {
            throw new LedgerException("conflicting duplicate funding command");
        }
        if (inserted == 0) {
            return false;
        }

        throw new LedgerException("unexpected funding idempotency insert result");
    }

    @Override
    @Transactional
    public void debitAvailable(AccountId accountId, Asset asset, long amount) {
        balanceForUpdate(accountId, asset).debitAvailable(amount);
    }

    @Override
    @Transactional
    public void reserve(AccountId accountId, Asset asset, long amount) {
        balanceForUpdate(accountId, asset).reserve(amount);
    }

    @Override
    @Transactional
    public void release(AccountId accountId, Asset asset, long amount) {
        balanceForUpdate(accountId, asset).release(amount);
    }

    @Override
    @Transactional
    public void settle(TradeSettlementInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");

        var makerBase = balanceForUpdate(instruction.makerAccountId(), instruction.baseAsset());
        var makerQuote = balanceForUpdate(instruction.makerAccountId(), instruction.quoteAsset());
        var takerBase = balanceForUpdate(instruction.takerAccountId(), instruction.baseAsset());
        var takerQuote = balanceForUpdate(instruction.takerAccountId(), instruction.quoteAsset());
        var quoteAmount = instruction.quoteAmount();

        if (instruction.makerSide() == SettlementSide.SELL) {
            makerBase.requireLocked(instruction.quantity());
            takerQuote.requireLocked(quoteAmount);
            makerQuote.requireCanCredit(quoteAmount);
            takerBase.requireCanCredit(instruction.quantity());
            makerBase.debitLocked(instruction.quantity());
            takerQuote.debitLocked(quoteAmount);
            makerQuote.credit(quoteAmount);
            takerBase.credit(instruction.quantity());
            return;
        }

        makerQuote.requireLocked(quoteAmount);
        takerBase.requireLocked(instruction.quantity());
        makerBase.requireCanCredit(instruction.quantity());
        takerQuote.requireCanCredit(quoteAmount);
        makerQuote.debitLocked(quoteAmount);
        takerBase.debitLocked(instruction.quantity());
        makerBase.credit(instruction.quantity());
        takerQuote.credit(quoteAmount);
    }

    @Override
    @Transactional
    public boolean settleExecution(String executionId, TradeSettlementInstruction instruction) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(instruction, "instruction must not be null");
        var payloadHash = settlementPayloadHash(instruction);
        if (!recordProcessedCommand(
            ProcessedCommandEntity.TRADE_SETTLEMENT,
            executionId,
            instruction.makerAccountId().value(),
            instruction.baseAsset().symbol() + "/" + instruction.quoteAsset().symbol(),
            instruction.quantity(),
            payloadHash
        )) {
            return false;
        }
        settle(instruction);
        return true;
    }

    @Override
    @Transactional
    public boolean releaseExecution(String executionId, AccountId accountId, Asset asset, long amount) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        if (amount <= 0) {
            throw new LedgerException("amount must be positive");
        }
        if (!recordProcessedCommand(
            ProcessedCommandEntity.BALANCE_RELEASE,
            executionId,
            accountId.value(),
            asset.symbol(),
            amount,
            releasePayloadHash(accountId, asset, amount)
        )) {
            return false;
        }
        release(accountId, asset, amount);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public AssetBalance balanceOf(AccountId accountId, Asset asset) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        return balances.findByAccountIdAndAssetSymbol(accountId.value(), asset.symbol())
            .map(LedgerBalanceEntity::toBalance)
            .orElseGet(AssetBalance::zero);
    }

    private LedgerBalanceEntity balanceForUpdate(AccountId accountId, Asset asset) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        return balances.findForUpdate(accountId.value(), asset.symbol())
            .orElseGet(() -> balances.save(new LedgerBalanceEntity(accountId.value(), asset.symbol())));
    }

    private boolean recordProcessedCommand(
        String commandType,
        String commandId,
        String accountId,
        String assetSymbol,
        long amount,
        String payloadHash
    ) {
        var inserted = processedCommands.insertIfAbsent(commandType, commandId, accountId, assetSymbol, amount, payloadHash);
        if (inserted == 1) {
            return true;
        }

        var existing = processedCommands.findByCommandTypeAndClientOrderId(commandType, commandId)
            .orElseThrow(() -> new LedgerException("processed command not found after conflict"));
        if (!existing.matchesPayloadHash(payloadHash)) {
            throw new LedgerException("conflicting duplicate ledger command");
        }
        if (inserted == 0) {
            return false;
        }

        throw new LedgerException("unexpected idempotency insert result");
    }

    private static String fundingPayloadHash(AccountId accountId, Asset asset, long amount) {
        return sha256("FUNDING_CREDIT|%s|%s|%d".formatted(accountId.value(), asset.symbol(), amount));
    }

    private static String settlementPayloadHash(TradeSettlementInstruction instruction) {
        return sha256("TRADE_SETTLEMENT|%s|%s|%s|%s|%s|%d|%d".formatted(
            instruction.makerAccountId().value(),
            instruction.takerAccountId().value(),
            instruction.makerSide(),
            instruction.baseAsset().symbol(),
            instruction.quoteAsset().symbol(),
            instruction.price(),
            instruction.quantity()
        ));
    }

    private static String releasePayloadHash(AccountId accountId, Asset asset, long amount) {
        return sha256("BALANCE_RELEASE|%s|%s|%d".formatted(accountId.value(), asset.symbol(), amount));
    }

    private static String sha256(String payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
