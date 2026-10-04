package br.com.mb.engine.command;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.memory.InMemoryLedger;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.model.Asset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EngineCommandHandlerTest {
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final EngineState state = new EngineState();
    private final List<CommandMessage> events = new ArrayList<>();
    private final EngineCommandHandler handler = new EngineCommandHandler(new CommandPublisher() {
        public void publish(CommandMessage message) { events.add(message); }
        public void close() {}
    }, "events", ignored -> {}, ledger, state);

    @Test
    void settlesTheExerciseExampleSynchronouslyAndRemovesBothOrders() {
        credit("A", "BRL", 600000);
        credit("B", "BTC", 1);
        assertTrue(order("sell", "B", "2", 500000, 1).accepted());
        assertBalance("B", "BTC", 0, 1);
        assertTrue(order("buy", "A", "1", 500000, 1).accepted());
        assertBalance("A", "BTC", 1, 0);
        assertBalance("A", "BRL", 100000, 0);
        assertBalance("B", "BTC", 0, 0);
        assertBalance("B", "BRL", 500000, 0);
        assertTrue(state.openOrders().isEmpty());
    }

    @Test
    void partialBuyKeepsOnlyRemainingReservationAndReleasesPriceImprovement() {
        credit("seller", "BTC", 4);
        credit("buyer", "BRL", 1100);
        order("sell", "seller", "2", 100, 4);
        var result = order("buy", "buyer", "1", 110, 10);
        assertTrue(result.accepted());
        assertEquals(3, result.eventFixMessages().size());
        assertBalance("buyer", "BRL", 40, 660);
        assertBalance("buyer", "BTC", 4, 0);
        assertBalance("seller", "BRL", 400, 0);
        assertTrue(cancel("cancel", "buyer", "buy").accepted());
        assertBalance("buyer", "BRL", 700, 0);
        assertTrue(state.openOrders().isEmpty());
    }

    @Test
    void sellTakerUsesBuyMakerPriceAndTransfersAssets() {
        credit("buyer", "BRL", 1100);
        credit("seller", "BTC", 10);
        order("buy", "buyer", "1", 110, 10);
        assertTrue(order("sell", "seller", "2", 100, 10).accepted());
        assertBalance("seller", "BRL", 1100, 0);
        assertBalance("buyer", "BTC", 10, 0);
        assertBalance("buyer", "BRL", 0, 0);
    }

    @Test
    void preservesTheBookAndAllBalancesWhenALaterFillWouldOverflow() {
        credit("first", "BTC", 1);
        credit("second", "BTC", 1);
        credit("second", "BRL", Long.MAX_VALUE);
        credit("buyer", "BRL", 200);
        order("s1", "first", "2", 100, 1);
        order("s2", "second", "2", 100, 1);
        var first = state.openOrder(new ClientOrderId("s1")).orElseThrow();
        var second = state.openOrder(new ClientOrderId("s2")).orElseThrow();
        var result = order("buy", "buyer", "1", 100, 2);
        assertFalse(result.accepted());
        assertEquals("balance overflow", result.detail());
        assertEquals(1, first.remainingQuantity());
        assertEquals(1, second.remainingQuantity());
        assertBalance("buyer", "BRL", 200, 0);
        assertBalance("first", "BRL", 0, 0);
        assertBalance("first", "BTC", 0, 1);
        assertBalance("second", "BRL", Long.MAX_VALUE, 0);
        assertEquals(2, state.openOrders().size());
    }

    @Test
    void rejectsSelfTradeBeforeAnyFillOrReservation() {
        credit("third", "BTC", 1);
        credit("owner", "BTC", 1);
        credit("owner", "BRL", 200);
        order("third-sell", "third", "2", 100, 1);
        order("own-sell", "owner", "2", 100, 1);
        assertFalse(order("own-buy", "owner", "1", 100, 2).accepted());
        assertBalance("owner", "BRL", 200, 0);
        assertBalance("third", "BTC", 0, 1);
        assertEquals(2, state.openOrders().size());
    }

    @Test
    void rejectsForeignOrWrongInstrumentCancellationAndAllowsTheOwner() {
        credit("owner", "BRL", 1000);
        order("buy", "owner", "1", 100, 10);
        assertFalse(cancel("foreign", "other", "buy").accepted());
        assertFalse(send("8=FIX.4.4|35=F|1=owner|11=wrong|41=buy|55=ETH/BRL|").accepted());
        assertBalance("owner", "BRL", 0, 1000);
        assertTrue(cancel("valid", "owner", "buy").accepted());
        assertBalance("owner", "BRL", 1000, 0);
        assertFalse(cancel("missing", "owner", "buy").accepted());
    }

    @Test
    void fundingAndOrdersAreIdempotentWithinTheSameProcess() {
        var funding = "8=FIX.4.4|35=U1|1=A|11=fund|55=BRL|38=1000|";
        assertTrue(send(funding).accepted());
        assertTrue(send(funding).accepted());
        assertFalse(send(funding.replace("38=1000", "38=2000")).accepted());
        var accepted = order("buy", "A", "1", 100, 4);
        assertEquals(accepted, order("buy", "A", "1", 100, 4));
        assertBalance("A", "BRL", 600, 400);
        assertTrue(cancel("cancel", "A", "buy").accepted());
        assertEquals(accepted, order("buy", "A", "1", 100, 4));
        assertTrue(state.openOrders().isEmpty());
        assertBalance("A", "BRL", 1000, 0);
    }

    @Test
    void debitsOnlyAvailableFundsAndRejectsInsufficientOrOverflowingOrders() {
        credit("A", "BRL", 1100);
        order("buy", "A", "1", 100, 10);
        assertTrue(send("8=FIX.4.4|35=U6|1=A|11=debit|55=BRL|38=50|").accepted());
        assertTrue(send("8=FIX.4.4|35=U6|1=A|11=debit|55=BRL|38=50|").accepted());
        assertFalse(send("8=FIX.4.4|35=U6|1=A|11=too-much|55=BRL|38=51|").accepted());
        assertFalse(order("insufficient", "A", "1", 100, 1).accepted());
        assertFalse(order("overflow", "A", "1", Long.MAX_VALUE, 2).accepted());
        assertBalance("A", "BRL", 50, 1000);
    }

    @Test
    void publicationRetryDoesNotRepeatMatchingOrSettlement() {
        var attempts = new ArrayList<CommandMessage>();
        var flaky = new EngineCommandHandler(new CommandPublisher() {
            public void publish(CommandMessage message) {
                attempts.add(message);
                if (attempts.size() == 1) throw new IllegalStateException("Kafka offline");
            }
            public void close() {}
        }, "events", ignored -> {}, ledger);
        var message = message("8=FIX.4.4|35=U1|1=A|11=fund|55=BRL|38=100|");
        assertThrows(IllegalStateException.class, () -> flaky.handle(message));
        flaky.handle(message);
        assertEquals(attempts.getFirst(), attempts.getLast());
        assertBalance("A", "BRL", 100, 0);
    }

    @Test
    void keepsExistingBookOrdersInMemoryBetweenCommands() {
        credit("seller", "BTC", 2);
        order("sell", "seller", "2", 100, 2);
        var original = state.openOrder(new ClientOrderId("sell")).orElseThrow();
        credit("other", "BRL", 100);
        assertSame(original, state.openOrder(new ClientOrderId("sell")).orElseThrow());
        credit("buyer", "BRL", 100);
        order("buy", "buyer", "1", 100, 1);
        assertSame(original, state.openOrder(new ClientOrderId("sell")).orElseThrow());
        assertEquals(1, original.remainingQuantity());
    }

    @Test
    void serializesConcurrentCommandsAndDeduplicatesConcurrentReplays() throws Exception {
        credit("A", "BRL", 100);
        var fix = "8=FIX.4.4|35=D|1=A|11=buy|55=BTC/BRL|54=1|44=100|38=1|";
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = workers.invokeAll(IntStream.range(0, 100)
                .mapToObj(i -> (Callable<EngineCommandResult>) () -> send(fix)).toList());
            for (var result : results) assertTrue(result.get().accepted());
        }
        assertEquals(1, state.openOrders().size());
        assertBalance("A", "BRL", 0, 100);
    }

    @Test
    void reportsInvalidProtocolAndUnknownMarketsThroughKafkaEvents() {
        handler.handle(new CommandMessage("commands", "A", "35=D|"));
        assertTrue(events.getLast().value().contains("35=j"));
        assertFalse(send("8=FIX.4.4|35=D|1=A|11=x|55=DOGE/BRL|54=1|44=100|38=1|").accepted());
        assertFalse(send("8=FIX.4.4|35=D|1=A|11=y|55=BTC/BRL|54=1|44=0|38=1|").accepted());
    }

    private void credit(String account, String asset, long amount) {
        ledger.credit(new AccountId(account), new Asset(asset), amount);
    }
    private EngineCommandResult order(String id, String account, String side, long price, long quantity) {
        return send("8=FIX.4.4|35=D|1=%s|11=%s|55=BTC/BRL|54=%s|44=%d|38=%d|".formatted(account, id, side, price, quantity));
    }
    private EngineCommandResult cancel(String id, String account, String original) {
        return send("8=FIX.4.4|35=F|1=%s|11=%s|41=%s|55=BTC/BRL|".formatted(account, id, original));
    }
    private EngineCommandResult send(String fix) { return handler.classify(message(fix)); }
    private static CommandMessage message(String fix) { return new CommandMessage("commands", FixMessage.parse(fix).kafkaKey(), fix); }
    private void assertBalance(String account, String asset, long available, long locked) {
        assertEquals(new AssetBalance(available, locked), ledger.balanceOf(new AccountId(account), new Asset(asset)));
    }
}
