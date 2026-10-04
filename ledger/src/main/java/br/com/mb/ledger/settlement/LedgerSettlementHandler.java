package br.com.mb.ledger.settlement;

import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.jooq.JooqLedger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.util.Objects;
import java.util.function.Consumer;

public final class LedgerSettlementHandler implements CommandHandler {

    private final Ledger ledger;
    private final LedgerSettlementParser parser;
    private final Consumer<String> output;

    public LedgerSettlementHandler(Ledger ledger, Consumer<String> output) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.parser = new LedgerSettlementParser();
    }

    @Override
    public void handle(CommandMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            var command = parser.parse(FixMessage.parse(message.value()));
            if (ledger instanceof JooqLedger durable) {
                var executionId = FixMessage.parse(message.value()).field(17).orElseThrow();
                durable.applyInstruction(executionId, message.value(), participating -> apply(command, participating));
            } else {
                apply(command, ledger);
            }
            output.accept("ACCEPTED key=%s command=%s".formatted(message.key(), command.getClass().getSimpleName()));
        } catch (LedgerException exception) {
            output.accept("%s key=%s reason=%s".formatted(ledger instanceof JooqLedger ? "FAILED" : "REJECTED",
                message.key(), exception.getMessage()));
            // Do not acknowledge failed financial effects. Durable status supports reconciliation.
            if (ledger instanceof JooqLedger) throw exception;
        } catch (InvalidFixMessageException exception) {
            if (ledger instanceof JooqLedger durable) durable.quarantineSettlement(message, exception.getMessage());
            output.accept("REJECTED key=%s reason=%s".formatted(message.key(), exception.getMessage()));
        }
    }

    private void apply(LedgerSettlementCommand command, Ledger target) {
        if (command instanceof TradeSettlementCommand trade) apply(trade, target);
        else if (command instanceof ReleaseCommand release) apply(release, target);
    }

    private void apply(TradeSettlementCommand command, Ledger target) {
        target.settleExecution(command.executionId(), new TradeSettlementInstruction(
            command.makerAccountId(),
            command.takerAccountId(),
            command.makerSide(),
            command.baseAsset(),
            command.quoteAsset(),
            command.price(),
            command.quantity()
        ));
    }

    private void apply(ReleaseCommand command, Ledger target) {
        target.releaseExecution(command.executionId(), command.accountId(), command.asset(), command.amount());
    }
}
