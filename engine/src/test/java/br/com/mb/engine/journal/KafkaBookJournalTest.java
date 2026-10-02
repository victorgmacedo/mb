package br.com.mb.engine.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderStatus;
import br.com.mb.engine.domain.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaBookJournalTest {

    private static final Instrument BTC_BRL = new Instrument("BTC/BRL");

    @Test
    void publishesAcceptedOrderMessage() {
        var publisher = new RecordingPublisher();
        var journal = new KafkaBookJournal(publisher, "book-journal");

        journal.appendAccepted(order("buyer-A", "buy-1", Side.BUY, 100, 10));

        assertEquals(1, publisher.messages().size());
        assertEquals("book-journal", publisher.messages().getFirst().topic());
        assertEquals("BTC/BRL", publisher.messages().getFirst().key());
        assertEquals(
            "8=FIX.4.4\u000135=U4\u000149=engine\u000156=engine\u00011=buyer-A\u000111=buy-1\u000155=BTC/BRL\u000154=1\u000144=100\u000138=10\u0001",
            publisher.messages().getFirst().value()
        );
    }

    @Test
    void publishesCancelledOrderMessage() {
        var publisher = new RecordingPublisher();
        var journal = new KafkaBookJournal(publisher, "book-journal");

        journal.appendCancelled(BookOrder.from(order("buyer-A", "buy-1", Side.BUY, 100, 10)));

        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=U5\u000149=engine\u000156=engine\u00011=buyer-A\u000141=buy-1\u000155=BTC/BRL\u0001",
            publisher.messages().getFirst().value()
        );
    }

    private static Order order(String accountId, String clientOrderId, Side side, long price, long quantity) {
        return new Order(
            new AccountId(accountId),
            new ClientOrderId(clientOrderId),
            BTC_BRL,
            side,
            price,
            quantity,
            OrderStatus.ACCEPTED
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
