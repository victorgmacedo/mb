package br.com.mb.engine.command;

import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.InvalidOrderException;
import br.com.mb.engine.domain.OrderIntake;
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

    public EngineCommandHandler(CommandPublisher eventPublisher, String eventsTopic, Consumer<String> output) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.eventsTopic = Objects.requireNonNull(eventsTopic, "eventsTopic must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.parser = new EngineCommandParser();
        this.eventFactory = new EngineEventFactory();
        this.orderIntake = new OrderIntake(InstrumentCatalog.defaultCatalog());
        this.engineState = new EngineState();
    }

    @Override
    public void handle(CommandMessage message) {
        var result = classify(message);
        eventPublisher.publish(new CommandMessage(eventsTopic, message.key(), result.eventFixMessage()));
        output.accept(result.line());
    }

    public EngineCommandResult classify(CommandMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            var fix = FixMessage.parse(message.value());
            var command = parser.parse(fix);
            if (command instanceof NewOrderSingleCommand) {
                engineState.place(orderIntake.accept(command));
            } else if (command instanceof CancelOrderCommand cancelOrder) {
                engineState.cancel(cancelOrder);
            }
            return EngineCommandResult.accepted(message.key(), command.getClass().getSimpleName(), eventFactory.accepted(command));
        } catch (InvalidFixMessageException | InvalidOrderException exception) {
            return EngineCommandResult.rejected(message.key(), exception.getMessage(), eventFactory.rejected(message.key(), exception.getMessage()));
        }
    }
}
