package br.com.mb.engine.command;

import br.com.mb.engine.command.strategy.CancelOrderStrategy;
import br.com.mb.engine.command.strategy.EngineCommandStrategy;
import br.com.mb.engine.command.strategy.FundingCreditStrategy;
import br.com.mb.engine.command.strategy.FundingDebitStrategy;
import br.com.mb.engine.command.strategy.NewOrderSingleStrategy;
import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.BookView;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.ledger.BalanceReservations;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import br.com.mb.shared.model.Asset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Serializes commands and queries over one in-memory exchange. */
public final class EngineCommandHandler implements CommandHandler {
    private final CommandPublisher publisher;
    private final String eventsTopic;
    private final Consumer<String> output;
    private final Ledger ledger;
    private final EngineState state;
    private final InstrumentCatalog instruments = InstrumentCatalog.defaultCatalog();
    private final EngineCommandParser parser = new EngineCommandParser();
    private final EngineEventFactory events = new EngineEventFactory();
    private final Map<String, CachedDecision> decisions = new HashMap<>();
    private final EngineCommandStrategy<NewOrderSingleCommand> newOrders;
    private final EngineCommandStrategy<CancelOrderCommand> cancellations;
    private final EngineCommandStrategy<FundingCreditCommand> credits;
    private final EngineCommandStrategy<FundingDebitCommand> debits;

    public EngineCommandHandler(CommandPublisher publisher, String eventsTopic, Consumer<String> output, Ledger ledger) {
        this(publisher, eventsTopic, output, ledger, new EngineState());
    }

    public EngineCommandHandler(CommandPublisher publisher, String eventsTopic, Consumer<String> output,
                                Ledger ledger, EngineState state) {
        this.publisher = Objects.requireNonNull(publisher);
        this.eventsTopic = Objects.requireNonNull(eventsTopic);
        this.output = Objects.requireNonNull(output);
        this.ledger = Objects.requireNonNull(ledger);
        this.state = Objects.requireNonNull(state);
        var reservations = new BalanceReservations(instruments, ledger);
        newOrders = new NewOrderSingleStrategy(new OrderIntake(instruments), state, reservations, events);
        cancellations = new CancelOrderStrategy(state, reservations, events);
        credits = new FundingCreditStrategy(ledger, events);
        debits = new FundingDebitStrategy(ledger, events);
    }

    @Override
    public void handle(CommandMessage message) {
        var result = classify(message);
        // A failed send may be retried by Kafka: classify returns the cached decision, without repeating effects.
        for (var event : result.eventFixMessages()) publisher.publish(new CommandMessage(eventsTopic, message.key(), event));
        output.accept(result.line());
    }

    public synchronized EngineCommandResult classify(CommandMessage message) {
        Objects.requireNonNull(message);
        try {
            var fix = FixMessage.parse(message.value());
            var command = parser.parse(fix);
            var id = fix.field(35).orElseThrow() + ":" + fix.field(11).orElseThrow();
            var previous = decisions.get(id);
            if (previous != null) {
                if (!previous.payload().equals(fix.normalized()) || !previous.key().equals(message.key())) {
                    return rejected(message, "conflicting duplicate command");
                }
                return previous.result();
            }
            var result = execute(message, fix, command);
            decisions.put(id, new CachedDecision(fix.normalized(), message.key(), result));
            return result;
        } catch (InvalidFixMessageException exception) {
            return rejected(message, exception.getMessage());
        }
    }

    private EngineCommandResult execute(CommandMessage message, FixMessage fix, EngineCommand command) {
        try {
            if (command instanceof CancelOrderCommand cancel) {
                var original = state.openOrder(new ClientOrderId(cancel.originalClientOrderId()));
                if (original.isPresent() && fix.field(55).isPresent()
                    && !original.orElseThrow().instrument().symbol().equals(fix.field(55).orElseThrow())) {
                    throw new InvalidOrderException("cancel instrument differs from original order");
                }
            }
            var responses = switch (command) {
                case NewOrderSingleCommand order -> newOrders.execute(order);
                case CancelOrderCommand cancel -> cancellations.execute(cancel);
                case FundingCreditCommand credit -> credits.execute(credit);
                case FundingDebitCommand debit -> debits.execute(debit);
            };
            return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), responses);
        } catch (InvalidOrderException | LedgerException | IllegalArgumentException exception) {
            return rejected(message, exception.getMessage());
        }
    }

    private EngineCommandResult rejected(CommandMessage message, String reason) {
        return EngineCommandResult.rejected(message.key(), reason, events.rejected(message.key(), reason));
    }

    public synchronized AssetBalance balanceOf(String account, String asset) {
        return ledger.balanceOf(new AccountId(account), new Asset(asset));
    }

    public synchronized Optional<BookView> book(String symbol) {
        return instruments.findBySymbol(symbol).map(instrument -> {
            var book = state.book(instrument);
            return new BookView(symbol, book.bidLevels(), book.askLevels());
        });
    }

    private record CachedDecision(String payload, String key, EngineCommandResult result) {}
}
