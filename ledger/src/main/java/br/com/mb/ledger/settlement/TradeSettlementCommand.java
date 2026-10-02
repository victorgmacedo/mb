package br.com.mb.ledger.settlement;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.shared.model.Asset;

record TradeSettlementCommand(
    String executionId,
    AccountId makerAccountId,
    AccountId takerAccountId,
    SettlementSide makerSide,
    Asset baseAsset,
    Asset quoteAsset,
    long price,
    long quantity
) implements LedgerSettlementCommand {
}
