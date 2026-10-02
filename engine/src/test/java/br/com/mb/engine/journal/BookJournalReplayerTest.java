package br.com.mb.engine.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.engine.domain.InstrumentCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookJournalReplayerTest {

    @Test
    void rebuildsOpenBookExactlyFromAcceptedMutations() {
        var messages = List.of(
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=seller-A|11=sell-1|55=BTC/BRL|54=2|44=100|38=10|10003=1|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=4|10003=2|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=buyer-B|11=buy-2|55=BTC/BRL|54=1|44=90|38=3|10003=3|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=seller-B|11=sell-2|55=BTC/BRL|54=2|44=120|38=7|10003=4|"),
            message("8=FIX.4.4|35=U5|49=engine|56=engine|1=buyer-B|41=buy-2|55=BTC/BRL|")
        );

        var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(messages);

        assertEquals(2, state.openOrders().size());
        assertEquals("sell-1", state.openOrders().get(0).clientOrderId());
        assertEquals(6, state.openOrders().get(0).remainingQuantity());
        assertEquals(1, state.openOrders().get(0).entrySequence());
        assertEquals("sell-2", state.openOrders().get(1).clientOrderId());
        assertEquals(7, state.openOrders().get(1).remainingQuantity());
        assertEquals(4, state.openOrders().get(1).entrySequence());
    }

    private static CommandMessage message(String fix) {
        return new CommandMessage("book-journal", "BTC/BRL", fix);
    }
}
