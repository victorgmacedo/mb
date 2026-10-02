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
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=account-A\u000111=order-1\u000117=accepted-order-1\u0001150=0\u000139=0\u000158=Order accepted\u0001",
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
    void rejectsUnknownInstrumentBeforePublishingAcceptance() {
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", line -> {});
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=DOGE/BRL|54=1|44=50000000|38=100000000|"
        );

        handler.handle(message);

        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=account-A\u000158=unknown instrument: DOGE/BRL\u0001",
            publisher.messages().getFirst().value()
        );
    }

    @Test
    void publishesEventAndWritesResultLineWhenHandlingCommand() {
        var lines = new ArrayList<String>();
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", lines::add);
        handler.handle(new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-0|55=BTC/BRL|54=1|44=50000000|38=100000000|"
        ));
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|11=order-1|41=order-0|"
        );

        handler.handle(message);

        assertEquals(2, lines.size());
        assertEquals("ACCEPTED key=account-A command=CancelOrderCommand", lines.get(1));
        assertEquals(2, publisher.messages().size());
        assertEquals("events", publisher.messages().get(1).topic());
        assertEquals("account-A", publisher.messages().get(1).key());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=gateway\u000111=order-1\u000117=accepted-order-1\u0001150=4\u000139=4\u000158=Order cancelled\u0001",
            publisher.messages().get(1).value()
        );
    }

    @Test
    void rejectsCancelForUnknownOpenOrder() {
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", line -> {});
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|11=cancel-1|41=missing|"
        );

        handler.handle(message);

        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=account-A\u000158=open order not found: missing\u0001",
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
