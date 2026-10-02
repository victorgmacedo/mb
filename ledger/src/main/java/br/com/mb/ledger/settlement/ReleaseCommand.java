package br.com.mb.ledger.settlement;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.shared.model.Asset;

record ReleaseCommand(String executionId, AccountId accountId, Asset asset, long amount) implements LedgerSettlementCommand {
}
