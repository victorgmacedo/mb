package br.com.mb.engine.command;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.shared.model.Asset;
import java.util.List;
import java.util.Objects;

public final class FundingDebitStrategy implements EngineCommandStrategy<FundingDebitCommand> {
    private final Ledger ledger;
    private final EngineEventFactory events;

    public FundingDebitStrategy(Ledger ledger, EngineEventFactory events) {
        this.ledger = Objects.requireNonNull(ledger);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public List<String> execute(FundingDebitCommand command) {
        ledger.debitFunding(command.clientOrderId(), new AccountId(command.accountId()),
            new Asset(command.asset()), command.amount());
        return events.accepted(command);
    }
}
