package br.com.mb.engine.command;

import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.journal.BookJournal;
import br.com.mb.engine.journal.ExecutionJournal;
import br.com.mb.engine.journal.NoOpBookJournal;
import br.com.mb.engine.journal.SynchronousLedgerJournal;
import br.com.mb.engine.ledger.BalanceReservations;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class EngineCommandHandler implements CommandHandler {

    private final CommandPublisher eventPublisher;
    private final String eventsTopic;
    private final Consumer<String> output;
    private final EngineCommandParser parser;
    private final EngineEventFactory eventFactory;
    private final EngineCommandStrategy<NewOrderSingleCommand> newOrderStrategy;
    private final EngineCommandStrategy<CancelOrderCommand> cancelOrderStrategy;
    private final EngineCommandStrategy<FundingCreditCommand> fundingCreditStrategy;
    private final EngineCommandStrategy<FundingDebitCommand> fundingDebitStrategy;

    public EngineCommandHandler(
        CommandPublisher eventPublisher,
        String eventsTopic,
        Consumer<String> output,
        Ledger ledger
    ) {
        this(eventPublisher, eventsTopic, output, ledger, null);
    }

    public EngineCommandHandler(
        CommandPublisher eventPublisher,
        String eventsTopic,
        Consumer<String> output,
        Ledger ledger,
        ExecutionJournal executionJournal
    ) {
        this(eventPublisher, eventsTopic, output, ledger, executionJournal, new NoOpBookJournal(), new EngineState());
    }

    public EngineCommandHandler(
        CommandPublisher eventPublisher,
        String eventsTopic,
        Consumer<String> output,
        Ledger ledger,
        ExecutionJournal executionJournal,
        BookJournal bookJournal,
        EngineState engineState
    ) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.eventsTopic = Objects.requireNonNull(eventsTopic, "eventsTopic must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(ledger, "ledger must not be null");
        Objects.requireNonNull(engineState, "engineState must not be null");
        Objects.requireNonNull(bookJournal, "bookJournal must not be null");
        var instrumentCatalog = InstrumentCatalog.defaultCatalog();
        this.parser = new EngineCommandParser();
        this.eventFactory = new EngineEventFactory();
        var reservations = new BalanceReservations(instrumentCatalog, ledger);
        var executions = executionJournal == null ? new SynchronousLedgerJournal(reservations) : executionJournal;
        this.newOrderStrategy = new NewOrderSingleStrategy(new OrderIntake(instrumentCatalog), engineState,
            reservations, bookJournal, executions, eventFactory);
        this.cancelOrderStrategy = new CancelOrderStrategy(engineState, reservations, bookJournal, eventFactory);
        this.fundingCreditStrategy = new FundingCreditStrategy(ledger, eventFactory);
        this.fundingDebitStrategy = new FundingDebitStrategy(ledger, eventFactory);
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
            return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), execute(command));
        } catch (InvalidFixMessageException | InvalidOrderException | LedgerException exception) {
            return EngineCommandResult.rejected(message.key(), exception.getMessage(), eventFactory.rejected(message.key(), exception.getMessage()));
        }
    }

    private List<String> execute(EngineCommand command) {
        return switch (command) {
            case NewOrderSingleCommand newOrder -> newOrderStrategy.execute(newOrder);
            case CancelOrderCommand cancel -> cancelOrderStrategy.execute(cancel);
            case FundingCreditCommand credit -> fundingCreditStrategy.execute(credit);
            case FundingDebitCommand debit -> fundingDebitStrategy.execute(debit);
        };
    }
}
