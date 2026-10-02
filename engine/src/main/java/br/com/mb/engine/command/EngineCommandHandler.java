package br.com.mb.engine.command;

import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.ledger.BalanceReservations;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.InMemoryLedger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.model.Asset;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.util.Objects;
import java.util.function.Consumer;

public final class EngineCommandHandler implements CommandHandler {

    private final CommandPublisher eventPublisher;
    private final String eventsTopic;
    private final Consumer<String> output;
    private final EngineCommandParser parser;
    private final EngineEventFactory eventFactory;
    private final OrderIntake orderIntake;
    private final EngineState engineState;
    private final BalanceReservations balanceReservations;
    private final InMemoryLedger ledger;

    public EngineCommandHandler(CommandPublisher eventPublisher, String eventsTopic, Consumer<String> output) {
        this(eventPublisher, eventsTopic, output, new InMemoryLedger());
    }

    public EngineCommandHandler(
        CommandPublisher eventPublisher,
        String eventsTopic,
        Consumer<String> output,
        InMemoryLedger ledger
    ) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.eventsTopic = Objects.requireNonNull(eventsTopic, "eventsTopic must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(ledger, "ledger must not be null");
        var instrumentCatalog = InstrumentCatalog.defaultCatalog();
        this.parser = new EngineCommandParser();
        this.eventFactory = new EngineEventFactory();
        this.orderIntake = new OrderIntake(instrumentCatalog);
        this.engineState = new EngineState();
        this.balanceReservations = new BalanceReservations(instrumentCatalog, ledger);
        this.ledger = ledger;
    }

    @Override
    public void handle(CommandMessage message) {
        var result = classify(message);
        for (var eventFixMessage : result.eventFixMessages()) {
            eventPublisher.publish(new CommandMessage(eventsTopic, message.key(), eventFixMessage));
        }
        output.accept(result.line());
    }

    public EngineCommandResult classify(CommandMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            var fix = FixMessage.parse(message.value());
            var command = parser.parse(fix);
            if (command instanceof NewOrderSingleCommand) {
                var order = orderIntake.accept(command);
                balanceReservations.reserve(order);
                try {
                    var placement = engineState.place(order);
                    return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), eventFactory.accepted((NewOrderSingleCommand) command, placement));
                } catch (InvalidOrderException exception) {
                    balanceReservations.release(br.com.mb.engine.book.BookOrder.from(order));
                    throw exception;
                }
            } else if (command instanceof CancelOrderCommand cancelOrder) {
                balanceReservations.release(engineState.cancel(cancelOrder));
            } else if (command instanceof FundingCreditCommand fundingCredit) {
                credit(fundingCredit);
            }
            return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), eventFactory.accepted(command));
        } catch (InvalidFixMessageException | InvalidOrderException | LedgerException exception) {
            return EngineCommandResult.rejected(message.key(), exception.getMessage(), eventFactory.rejected(message.key(), exception.getMessage()));
        }
    }

    private void credit(FundingCreditCommand command) {
        ledger.credit(new AccountId(command.accountId()), new Asset(command.asset()), command.amount());
    }
}
