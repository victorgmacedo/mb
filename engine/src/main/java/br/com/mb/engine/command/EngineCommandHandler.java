package br.com.mb.engine.command;

import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import java.util.Objects;
import java.util.function.Consumer;

public final class EngineCommandHandler implements CommandHandler {

    private final Consumer<String> output;

    public EngineCommandHandler(Consumer<String> output) {
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    @Override
    public void handle(CommandMessage message) {
        output.accept(classify(message).line());
    }

    public EngineCommandResult classify(CommandMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            var fix = FixMessage.parse(message.value());
            return EngineCommandResult.accepted(message.key(), fix.messageType().name());
        } catch (InvalidFixMessageException exception) {
            return EngineCommandResult.rejected(message.key(), exception.getMessage());
        }
    }
}
