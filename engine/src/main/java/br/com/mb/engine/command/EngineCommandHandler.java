package br.com.mb.engine.command;

import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.journal.BookJournal;
import br.com.mb.engine.journal.ExecutionJournal;
import br.com.mb.engine.journal.NoOpBookJournal;
import br.com.mb.engine.journal.SynchronousLedgerJournal;
import br.com.mb.engine.ledger.BalanceReservations;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.jooq.PostgresLedgerFactory;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import br.com.mb.shared.model.Asset;
import java.time.Instant;
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
    private final ExecutionJournal executionJournal;
    private final BookJournal bookJournal;
    private final Ledger ledger;

    public EngineCommandHandler(CommandPublisher eventPublisher, String eventsTopic, Consumer<String> output) {
        this(eventPublisher, eventsTopic, output, PostgresLedgerFactory.create());
    }

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
        this.engineState = Objects.requireNonNull(engineState, "engineState must not be null");
        this.bookJournal = Objects.requireNonNull(bookJournal, "bookJournal must not be null");
        var instrumentCatalog = InstrumentCatalog.defaultCatalog();
        this.parser = new EngineCommandParser();
        this.eventFactory = new EngineEventFactory();
        this.orderIntake = new OrderIntake(instrumentCatalog);
        this.balanceReservations = new BalanceReservations(instrumentCatalog, ledger);
        this.executionJournal = executionJournal == null
            ? new SynchronousLedgerJournal(balanceReservations)
            : executionJournal;
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
                if (engineState.hasOpenOrder(order.clientOrderId())) {
                    throw new InvalidOrderException("duplicate client order id: " + order.clientOrderId().value());
                }
                engineState.book(order.instrument()).validateSelfTrade(order);
                balanceReservations.reserve(order);
                var entrySequence = engineState.nextEntrySequence();
                var enteredAt = Instant.now();
                bookJournal.appendAccepted(order, entrySequence, enteredAt);
                PlacementResult placement;
                try {
                    placement = engineState.place(order, entrySequence, enteredAt);
                } catch (InvalidOrderException exception) {
                    releaseUnsettled(order);
                    throw exception;
                }
                executionJournal.append(order, placement);
                return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), eventFactory.accepted((NewOrderSingleCommand) command, placement));
            } else if (command instanceof CancelOrderCommand cancelOrder) {
                var originalClientOrderId = new ClientOrderId(cancelOrder.originalClientOrderId());
                var openOrder = engineState.openOrder(originalClientOrderId)
                    .orElseThrow(() -> new InvalidOrderException("open order not found: " + originalClientOrderId.value()));
                if (!openOrder.accountId().value().equals(cancelOrder.accountId())) {
                    throw new InvalidOrderException("order belongs to another account");
                }
                bookJournal.appendCancelled(openOrder);
                balanceReservations.release(engineState.cancel(cancelOrder));
            } else if (command instanceof FundingCreditCommand fundingCredit) {
                credit(fundingCredit);
            } else if (command instanceof FundingDebitCommand debit) {
                ledger.debitFunding(debit.clientOrderId(), new AccountId(debit.accountId()),
                    new Asset(debit.asset()), debit.amount());
            }
            return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), eventFactory.accepted(command));
        } catch (InvalidFixMessageException | InvalidOrderException | LedgerException exception) {
            return EngineCommandResult.rejected(message.key(), exception.getMessage(), eventFactory.rejected(message.key(), exception.getMessage()));
        }
    }

    private void credit(FundingCreditCommand command) {
        var accountId = new AccountId(command.accountId());
        var asset = new Asset(command.asset());
        ledger.creditFunding(command.clientOrderId(), accountId, asset, command.amount());
    }

    private void releaseUnsettled(Order order) {
        balanceReservations.release(BookOrder.from(order));
    }
}
