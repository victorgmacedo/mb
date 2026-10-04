package br.com.mb.engine.command;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.shared.model.Asset;
import java.util.List;
import java.util.Objects;

public final class FundingCreditStrategy implements EngineCommandStrategy<FundingCreditCommand> {
    private final Ledger ledger;
    private final EngineEventFactory events;

    public FundingCreditStrategy(Ledger ledger, EngineEventFactory events) {
        this.ledger = Objects.requireNonNull(ledger);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public List<String> execute(FundingCreditCommand command) {
        ledger.creditFunding(command.clientOrderId(), new AccountId(command.accountId()),
            new Asset(command.asset()), command.amount());
        return events.accepted(command);
    }
}
