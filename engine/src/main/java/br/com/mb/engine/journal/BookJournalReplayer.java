package br.com.mb.engine.journal;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.engine.command.CancelOrderCommand;
import br.com.mb.engine.command.OrderSide;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.OrderIntake;
import br.com.mb.engine.command.NewOrderSingleCommand;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class BookJournalReplayer {

    private final OrderIntake orderIntake;

    public BookJournalReplayer(InstrumentCatalog instrumentCatalog) {
        this.orderIntake = new OrderIntake(Objects.requireNonNull(instrumentCatalog, "instrumentCatalog must not be null"));
    }

    public EngineState replay(Iterable<CommandMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        var state = new EngineState();
        return replay(state, messages);
    }

    public EngineState replay(EngineState state, Iterable<CommandMessage> messages) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        for (var message : messages) {
            replay(state, message);
        }
        return state;
    }

    private void replay(EngineState state, CommandMessage message) {
        var fix = FixMessage.parse(message.value());
        switch (fix.messageType()) {
            case BOOK_ORDER_ACCEPTED -> state.place(orderIntake.accept(new NewOrderSingleCommand(
                required(fix, 1, "Account"),
                required(fix, 11, "ClOrdID"),
                required(fix, 55, "Symbol"),
                OrderSide.fromFixValue(required(fix, 54, "Side")),
                parseLong(required(fix, 44, "Price"), "Price(44)"),
                parseLong(required(fix, 38, "OrderQty"), "OrderQty(38)")
            )),
                parseLong(required(fix, 10003, "EntrySequence"), "EntrySequence(10003)"),
                parseInstant(required(fix, 10004, "EnteredAt"))
            );
            case BOOK_ORDER_CANCELLED -> state.cancel(new CancelOrderCommand(
                fix.field(1).orElseGet(fix::kafkaKey),
                required(fix, 41, "OrigClOrdID"),
                required(fix, 41, "OrigClOrdID")
            ));
            case NEW_ORDER_SINGLE, ORDER_CANCEL_REQUEST, FUNDING_CREDIT, LEDGER_TRADE_SETTLEMENT, LEDGER_RELEASE, EXECUTION_REPORT, BUSINESS_MESSAGE_REJECT ->
                throw new InvalidFixMessageException("Unsupported book journal MsgType(35): " + fix.messageType().tagValue());
        }
    }

    private static String required(FixMessage message, int tag, String name) {
        return message.field(tag)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new InvalidFixMessageException("FIX message requires " + name + "(" + tag + ")"));
    }

    private static long parseLong(String value, String name) {
        try {
            var parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new InvalidFixMessageException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidFixMessageException("Invalid " + name + ": " + value, exception);
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidFixMessageException("Invalid EnteredAt(10004): " + value, exception);
        }
    }
}
