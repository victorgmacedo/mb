package br.com.mb.ledger.jpa;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.model.Asset;
import java.util.Objects;
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

        var existing = processedCommands.findByCommandTypeAndClientOrderId(ProcessedCommandEntity.FUNDING_CREDIT, clientOrderId);
        if (existing.isPresent()) {
            if (!existing.get().matchesFundingCredit(accountId, asset, amount)) {
                throw new LedgerException("conflicting duplicate funding command");
            }
            return false;
        }

        processedCommands.save(new ProcessedCommandEntity(
            ProcessedCommandEntity.FUNDING_CREDIT,
            clientOrderId,
            accountId.value(),
            asset.symbol(),
            amount
        ));
        balanceForUpdate(accountId, asset).credit(amount);
        return true;
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
}
