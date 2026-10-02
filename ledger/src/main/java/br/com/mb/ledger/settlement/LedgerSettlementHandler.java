package br.com.mb.ledger.settlement;

import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.ledger.domain.Ledger;
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
            if (command instanceof TradeSettlementCommand tradeSettlement) {
                apply(tradeSettlement);
            } else if (command instanceof ReleaseCommand release) {
                apply(release);
            }
            output.accept("ACCEPTED key=%s command=%s".formatted(message.key(), command.getClass().getSimpleName()));
        } catch (InvalidFixMessageException | LedgerException exception) {
            output.accept("REJECTED key=%s reason=%s".formatted(message.key(), exception.getMessage()));
        }
    }

    private void apply(TradeSettlementCommand command) {
        ledger.settleExecution(command.executionId(), new TradeSettlementInstruction(
            command.makerAccountId(),
            command.takerAccountId(),
            command.makerSide(),
            command.baseAsset(),
            command.quoteAsset(),
            command.price(),
            command.quantity()
        ));
    }

    private void apply(ReleaseCommand command) {
        ledger.releaseExecution(command.executionId(), command.accountId(), command.asset(), command.amount());
    }
}
