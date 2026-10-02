package br.com.mb.engine.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.Trade;
import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Instrument;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.OrderStatus;
import br.com.mb.engine.domain.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KafkaSettlementJournalTest {

    @Test
    void publishesTradeSettlementAndPriceImprovementReleaseMessages() {
        var publisher = new RecordingPublisher();
        var journal = new KafkaSettlementJournal(publisher, "settlements");
        var takerOrder = new Order(
            new AccountId("buyer-A"),
            new ClientOrderId("buy-1"),
            new Instrument("BTC/BRL"),
            Side.BUY,
            110,
            10,
            OrderStatus.ACCEPTED
        );
        var trade = new Trade(
            new AccountId("seller-A"),
            new ClientOrderId("sell-1"),
            Side.SELL,
            new AccountId("buyer-A"),
            new ClientOrderId("buy-1"),
            100,
            10,
            0,
            0
        );

        journal.append(takerOrder, new PlacementResult(List.of(trade), Optional.empty()));

        assertEquals(2, publisher.messages().size());
        assertEquals("settlements", publisher.messages().get(0).topic());
        assertEquals("BTC/BRL", publisher.messages().get(0).key());
        assertEquals(
            "8=FIX.4.4\u000135=U2\u000149=engine\u000156=ledger\u000117=settle-buy-1-1\u000155=BTC/BRL\u000154=2\u000144=100\u000138=10\u000110001=seller-A\u000110002=buyer-A\u000141=sell-1\u000111=buy-1\u0001",
            publisher.messages().get(0).value()
        );
        assertEquals(
            "8=FIX.4.4\u000135=U3\u000149=engine\u000156=ledger\u000117=release-buy-1-1\u00011=buyer-A\u000111=buy-1\u000155=BRL\u000138=100\u0001",
            publisher.messages().get(1).value()
        );
    }

    @Test
    void skipsPriceImprovementReleaseWhenTradePriceEqualsLimitPrice() {
        var publisher = new RecordingPublisher();
        var journal = new KafkaSettlementJournal(publisher, "settlements");
        var takerOrder = new Order(
            new AccountId("buyer-A"),
            new ClientOrderId("buy-1"),
            new Instrument("BTC/BRL"),
            Side.BUY,
            100,
            10,
            OrderStatus.ACCEPTED
        );
        var trade = new Trade(
            new AccountId("seller-A"),
            new ClientOrderId("sell-1"),
            Side.SELL,
            new AccountId("buyer-A"),
            new ClientOrderId("buy-1"),
            100,
            10,
            0,
            0
        );

        journal.append(takerOrder, new PlacementResult(List.of(trade), Optional.empty()));

        assertEquals(1, publisher.messages().size());
        assertEquals("8=FIX.4.4\u000135=U2", publisher.messages().getFirst().value().substring(0, 15));
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
