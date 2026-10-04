package br.com.mb.engine.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.snapshot.BookSnapshotCodec;
import br.com.mb.engine.snapshot.BookSnapshotRestorer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookJournalReplayerTest {

    @Test
    void rebuildsOpenBookExactlyFromAcceptedMutations() {
        var messages = List.of(
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=seller-A|11=sell-1|55=BTC/BRL|54=2|44=100|38=10|10003=1|10004=2026-10-02T12:00:00Z|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=buyer-A|11=buy-1|55=BTC/BRL|54=1|44=100|38=4|10003=2|10004=2026-10-02T12:00:01Z|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=buyer-B|11=buy-2|55=BTC/BRL|54=1|44=90|38=3|10003=3|10004=2026-10-02T12:00:02Z|"),
            message("8=FIX.4.4|35=U4|49=engine|56=engine|1=seller-B|11=sell-2|55=BTC/BRL|54=2|44=120|38=7|10003=4|10004=2026-10-02T12:00:03Z|"),
            message("8=FIX.4.4|35=U5|49=engine|56=engine|1=buyer-B|41=buy-2|55=BTC/BRL|")
        );

        var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(messages);

        assertEquals(2, state.openOrders().size());
        assertEquals("sell-1", state.openOrders().get(0).clientOrderId());
        assertEquals(6, state.openOrders().get(0).remainingQuantity());
        assertEquals(1, state.openOrders().get(0).entrySequence());
        assertEquals(Instant.parse("2026-10-02T12:00:00Z"), state.openOrders().get(0).enteredAt());
        assertEquals("sell-2", state.openOrders().get(1).clientOrderId());
        assertEquals(7, state.openOrders().get(1).remainingQuantity());
        assertEquals(4, state.openOrders().get(1).entrySequence());
        assertEquals(Instant.parse("2026-10-02T12:00:03Z"), state.openOrders().get(1).enteredAt());
    }

    @Test
    void replaysHistoricalSelfTradeWithoutApplyingNewIntakePolicy() {
        var state = new BookJournalReplayer(InstrumentCatalog.defaultCatalog()).replay(List.of(
            message("8=FIX.4.4|35=U4|1=account-A|11=sell|55=BTC/BRL|54=2|44=100|38=10|10003=1|10004=2026-10-02T12:00:00Z|"),
            message("8=FIX.4.4|35=U4|1=account-A|11=buy|55=BTC/BRL|54=1|44=100|38=4|10003=2|10004=2026-10-02T12:00:01Z|")
        ));

        assertEquals(1, state.openOrders().size());
        assertEquals(6, state.openOrders().getFirst().remainingQuantity());
        assertEquals(2, state.lastEntrySequence());
    }

    @Test
    void preservesPerInstrumentWatermarksAcrossDuplicateDeliveryAndSnapshots() {
        var btc = message("8=FIX.4.4|35=U4|1=buyer|11=btc|55=BTC/BRL|54=1|44=90|38=3|10003=1|10004=2026-10-02T12:00:00Z|10005=one|");
        var eth = message("8=FIX.4.4|35=U4|1=buyer|11=eth|55=ETH/BRL|54=1|44=90|38=3|10003=2|10004=2026-10-02T12:00:01Z|10005=two|");
        var cancel = message("8=FIX.4.4|35=U5|1=buyer|41=btc|55=BTC/BRL|10003=3|10005=three|");
        var replayer = new BookJournalReplayer(InstrumentCatalog.defaultCatalog());
        // A checkpoint may cover different end offsets for different partitions.
        var state = replayer.replay(List.of(eth));
        var codec = new BookSnapshotCodec();
        var initial = state;
        var snapshots = state.books().stream().map(book -> codec.decode(codec.encode(book, initial.lastEntrySequence()))).toList();
        state = new BookSnapshotRestorer().restore(snapshots);
        replayer.replay(state, List.of(cancel, btc, eth, btc, cancel));
        assertEquals(1, state.openOrders().size());
        assertEquals("eth", state.openOrders().getFirst().clientOrderId());
        assertEquals(3, state.lastEntrySequence());
        var completed = state;
        snapshots = state.books().stream().map(book -> codec.decode(codec.encode(book, completed.lastEntrySequence()))).toList();
        var restored = new BookSnapshotRestorer().restore(snapshots);
        replayer.replay(restored, List.of(btc, cancel, eth));
        assertEquals(state.openOrders(), restored.openOrders());
    }

    private static CommandMessage message(String fix) {
        return new CommandMessage("book-journal", "BTC/BRL", fix);
    }
}
