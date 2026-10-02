package br.com.mb.engine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.InMemoryLedger;
import br.com.mb.shared.model.Asset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {

    @Test
    void acceptsNewOrderSingleFixCommandAndBuildsExecutionReport() {
        var publisher = new RecordingPublisher();
        var handler = fundedHandler(publisher, funding("account-A", "BRL", Long.MAX_VALUE));
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
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=account-A\u000111=order-1\u000117=accepted-order-1\u0001150=0\u000139=0\u000131=0\u000132=0\u0001151=0\u000158=Order accepted\u0001",
            result.eventFixMessages().getFirst()
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
            result.eventFixMessages().getFirst()
        );
    }

    @Test
    void rejectsUnknownInstrumentBeforePublishingAcceptance() {
        var publisher = new RecordingPublisher();
        var handler = fundedHandler(publisher, funding("account-A", "BRL", Long.MAX_VALUE));
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
        var handler = fundedHandler(publisher, lines::add, funding("account-A", "BRL", Long.MAX_VALUE));
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
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=gateway\u000111=order-1\u000117=accepted-order-1\u0001150=4\u000139=4\u000131=0\u000132=0\u0001151=0\u000158=Order cancelled\u0001",
            publisher.messages().get(1).value()
        );
    }

    @Test
    void publishesMakerAndTakerFillEventsWhenOrderMatches() {
        var publisher = new RecordingPublisher();
        var handler = fundedHandler(
            publisher,
            funding("seller-A", "BTC", Long.MAX_VALUE),
            funding("buyer-A", "BRL", Long.MAX_VALUE)
        );
        handler.handle(new CommandMessage(
            "commands",
            "seller-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=seller-A|11=sell-1|55=BTC/BRL|54=2|44=100|38=10|"
        ));

        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=110|38=10|"
        ));

        assertEquals(3, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=seller-A\u000111=sell-1\u000117=trade-buy-1-1-maker\u0001150=F\u000139=2\u000131=100\u000132=10\u0001151=0\u000158=Maker fill\u0001",
            publisher.messages().get(1).value()
        );
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=buyer-A\u000111=buy-1\u000117=trade-buy-1-1-taker\u0001150=F\u000139=2\u000131=100\u000132=10\u0001151=0\u000158=Taker fill\u0001",
            publisher.messages().get(2).value()
        );
    }

    @Test
    void rejectsCancelForFilledMakerOrder() {
        var publisher = new RecordingPublisher();
        var handler = fundedHandler(
            publisher,
            funding("seller-A", "BTC", Long.MAX_VALUE),
            funding("buyer-A", "BRL", Long.MAX_VALUE)
        );
        handler.handle(new CommandMessage(
            "commands",
            "seller-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=seller-A|11=sell-1|55=BTC/BRL|54=2|44=100|38=10|"
        ));
        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=110|38=10|"
        ));

        handler.handle(new CommandMessage(
            "commands",
            "seller-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|1=seller-A|11=cancel-1|41=sell-1|"
        ));

        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=seller-A\u000158=open order not found: sell-1\u0001",
            publisher.messages().getLast().value()
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

    @Test
    void rejectsNewOrderWhenAvailableBalanceIsInsufficient() {
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", line -> {});
        var message = new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=10|"
        );

        handler.handle(message);

        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=buyer-A\u000158=insufficient available balance\u0001",
            publisher.messages().getFirst().value()
        );
    }

    @Test
    void creditsAvailableBalanceWhenFundingCommandIsAccepted() {
        var publisher = new RecordingPublisher();
        var ledger = new InMemoryLedger();
        var handler = new EngineCommandHandler(publisher, "events", line -> {}, ledger);

        handler.handle(new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=U1|49=gateway|56=engine|1=account-A|11=funding-1|55=BRL|38=1000|"
        ));

        assertEquals(new AssetBalance(1_000, 0), ledger.account(new AccountId("account-A")).balanceOf(new Asset("BRL")));
        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=account-A\u000111=funding-1\u000117=accepted-funding-1\u0001150=0\u000139=0\u000131=0\u000132=0\u0001151=0\u000158=Funding credited\u0001",
            publisher.messages().getFirst().value()
        );
    }

    @Test
    void acceptsOrderAfterFundingCredit() {
        var publisher = new RecordingPublisher();
        var ledger = new InMemoryLedger();
        var handler = new EngineCommandHandler(publisher, "events", line -> {}, ledger);

        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=U1|49=gateway|56=engine|1=buyer-A|11=funding-1|55=BRL|38=1000|"
        ));
        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=10|"
        ));

        assertEquals(new AssetBalance(0, 1_000), ledger.account(new AccountId("buyer-A")).balanceOf(new Asset("BRL")));
        assertEquals(2, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=buyer-A\u000111=buy-1\u000117=accepted-buy-1\u0001150=0\u000139=0\u000131=0\u000132=0\u0001151=0\u000158=Order accepted\u0001",
            publisher.messages().getLast().value()
        );
    }

    @Test
    void rejectsInvalidFundingCredit() {
        var publisher = new RecordingPublisher();
        var handler = new EngineCommandHandler(publisher, "events", line -> {});

        handler.handle(new CommandMessage(
            "commands",
            "account-A",
            "8=FIX.4.4|35=U1|49=gateway|56=engine|1=account-A|11=funding-1|55=BRL|38=0|"
        ));

        assertEquals(1, publisher.messages().size());
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=account-A\u000158=Amount(38) must be positive\u0001",
            publisher.messages().getFirst().value()
        );
    }

    @Test
    void releasesReservedBalanceWhenOpenOrderIsCancelled() {
        var publisher = new RecordingPublisher();
        var ledger = new InMemoryLedger();
        ledger.credit(new AccountId("buyer-A"), new Asset("BRL"), 1_000);
        var handler = new EngineCommandHandler(publisher, "events", line -> {}, ledger);

        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=10|"
        ));
        assertEquals(new AssetBalance(0, 1_000), ledger.account(new AccountId("buyer-A")).balanceOf(new Asset("BRL")));

        handler.handle(new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=F|49=gateway|56=engine|1=buyer-A|11=cancel-1|41=buy-1|"
        ));

        assertEquals(new AssetBalance(1_000, 0), ledger.account(new AccountId("buyer-A")).balanceOf(new Asset("BRL")));
    }

    @Test
    void reservesBaseAssetForSellOrder() {
        var publisher = new RecordingPublisher();
        var ledger = new InMemoryLedger();
        ledger.credit(new AccountId("seller-A"), new Asset("BTC"), 10);
        var handler = new EngineCommandHandler(publisher, "events", line -> {}, ledger);

        handler.handle(new CommandMessage(
            "commands",
            "seller-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=seller-A|11=sell-1|55=BTC/BRL|54=2|44=100|38=10|"
        ));

        assertEquals(new AssetBalance(0, 10), ledger.account(new AccountId("seller-A")).balanceOf(new Asset("BTC")));
    }

    @Test
    void releasesReservationWhenBookRejectsDuplicateOrder() {
        var publisher = new RecordingPublisher();
        var ledger = new InMemoryLedger();
        ledger.credit(new AccountId("buyer-A"), new Asset("BRL"), 2_000);
        var handler = new EngineCommandHandler(publisher, "events", line -> {}, ledger);
        var duplicate = new CommandMessage(
            "commands",
            "buyer-A",
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=10|"
        );

        handler.handle(duplicate);
        handler.handle(duplicate);

        assertEquals(new AssetBalance(1_000, 1_000), ledger.account(new AccountId("buyer-A")).balanceOf(new Asset("BRL")));
        assertEquals(
            "8=FIX.4.4\u000135=j\u000149=engine\u000156=buyer-A\u000158=duplicate client order id: buy-1\u0001",
            publisher.messages().getLast().value()
        );
    }

    private static EngineCommandHandler fundedHandler(RecordingPublisher publisher, Funding... funds) {
        return fundedHandler(publisher, line -> {}, funds);
    }

    private static EngineCommandHandler fundedHandler(RecordingPublisher publisher, java.util.function.Consumer<String> output, Funding... funds) {
        var ledger = new InMemoryLedger();
        for (var funding : funds) {
            ledger.credit(new AccountId(funding.accountId()), new Asset(funding.asset()), funding.amount());
        }
        return new EngineCommandHandler(publisher, "events", output, ledger);
    }

    private static Funding funding(String accountId, String asset, long amount) {
        return new Funding(accountId, asset, amount);
    }

    private record Funding(String accountId, String asset, long amount) {
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
