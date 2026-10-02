package br.com.mb.ledger.settlement;

sealed interface LedgerSettlementCommand permits ReleaseCommand, TradeSettlementCommand {

    String executionId();
}
