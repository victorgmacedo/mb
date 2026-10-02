package br.com.mb.engine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.commandlog.CommandMessage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {

    @Test
    void acceptsNewOrderSingleFixCommand() {
        var handler = new EngineCommandHandler(line -> {});
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|"
        );

        var result = handler.classify(message);

        assertTrue(result.accepted());
        assertEquals("account-A", result.key());
        assertEquals("NEW_ORDER_SINGLE", result.detail());
    }

    @Test
    void rejectsInvalidFixCommand() {
        var handler = new EngineCommandHandler(line -> {});
        var message = new CommandMessage("commands", "account-A", "35=D|49=gateway|");

        var result = handler.classify(message);

        assertFalse(result.accepted());
        assertEquals("FIX message requires BeginString(8)", result.detail());
    }

    @Test
    void writesResultLineWhenHandlingCommand() {
        var lines = new ArrayList<String>();
        var handler = new EngineCommandHandler(lines::add);
        var message = new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|11=order-1|41=order-0|"
        );

        handler.handle(message);

        assertEquals(1, lines.size());
        assertEquals("ACCEPTED key=account-A type=ORDER_CANCEL_REQUEST", lines.getFirst());
    }
}
