package br.com.mb.engine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {

    @Test
    void acceptsNewOrderSingleFixCommandAndBuildsExecutionReport() {
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", line -> {});
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=100000000|"
        );

        var result = handler.classify(message);

        assertTrue(result.accepted());
        assertEquals("account-A", result.key());
        assertEquals("NewOrderSingleCommand", result.detail());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=account-A\u000111=order-1\u000117=accepted-order-1\u0001150=A\u000139=A\u000158=Command accepted\u0001",
            result.eventFixMessage()
        );
    }

    @Test
    void rejectsInvalidFixCommandAndBuildsBusinessReject() {
        var handler = new EngineCommandHandler(new RecordingPublisher(), "events", line -> {});
        var message = new CommandMessage("commands", "account-A", "35=D|49=gateway|");

        var result = handler.classify(message);

        assertFalse(result.accepted());
        assertEquals("FIX message requires BeginString(8)", result.detail());
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=account-A\u000158=FIX message requires BeginString(8)\u0001",
            result.eventFixMessage()
        );
    }

    @Test
    void publishesEventAndWritesResultLineWhenHandlingCommand() {
        var lines = new ArrayList<String>();
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", lines::add);
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|11=order-1|41=order-0|"
        );

        handler.handle(message);

        assertEquals(1, lines.size());
        assertEquals("ACCEPTED key=account-A command=CancelOrderCommand", lines.getFirst());
        assertEquals(1, publisher.messages().size());
        assertEquals("events", publisher.messages().getFirst().topic());
        assertEquals("account-A", publisher.messages().getFirst().key());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=gateway\u000111=order-1\u000117=accepted-order-1\u0001150=6\u000139=6\u000158=Cancel command accepted\u0001",
            publisher.messages().getFirst().value()
        );
    }

    private static final class RecordingPublisher implements CommandPublisher {

        private final List<CommandMessage> messages = new ArrayList<>();

        @Override
        public void publish(CommandMessage message) {
            messages.add(message);
        }

        @Override
        public void close() {
        }

        private List<CommandMessage> messages() {
            return messages;
        }
    }
}
