package br.com.mb.ledger.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.model.Asset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerSettlementHandlerTest {

    @Test
    void appliesTradeSettlementMessage() {
        var ledger = new RecordingLedger();
        var lines = new ArrayList<String>();
        var handler = new LedgerSettlementHandler(ledger, lines::add);

        handler.handle(new CommandMessage(
            "settlements",
            "BTC/BRL",
            "8=FIX.4.4|35=U2|49=engine|56=ledger|17=settle-buy-1-1|55=BTC/BRL|54=2|44=100|38=10|10001=seller-A|10002=buyer-A|41=sell-1|11=buy-1|"
        ));

        assertEquals(1, ledger.settlements().size());
        assertEquals("settle-buy-1-1", ledger.settlements().getFirst().executionId());
        assertEquals("seller-A", ledger.settlements().getFirst().instruction().makerAccountId().value());
        assertEquals("buyer-A", ledger.settlements().getFirst().instruction().takerAccountId().value());
        assertEquals("ACCEPTED key=BTC/BRL command=TradeSettlementCommand", lines.getFirst());
    }

    @Test
    void appliesReleaseMessage() {
        var ledger = new RecordingLedger();
        var lines = new ArrayList<String>();
        var handler = new LedgerSettlementHandler(ledger, lines::add);

        handler.handle(new CommandMessage(
            "settlements",
            "BTC/BRL",
            "8=FIX.4.4|35=U3|49=engine|56=ledger|17=release-buy-1-1|1=buyer-A|11=buy-1|55=BRL|38=100|"
        ));

        assertEquals(1, ledger.releases().size());
        assertEquals("release-buy-1-1", ledger.releases().getFirst().executionId());
        assertEquals("buyer-A", ledger.releases().getFirst().accountId().value());
        assertEquals(new Asset("BRL"), ledger.releases().getFirst().asset());
        assertEquals(100, ledger.releases().getFirst().amount());
        assertEquals("ACCEPTED key=BTC/BRL command=ReleaseCommand", lines.getFirst());
    }

    @Test
    void acceptsDuplicateSettlementWithoutRecordingSecondApplication() {
        var ledger = new RecordingLedger();
        var lines = new ArrayList<String>();
        var handler = new LedgerSettlementHandler(ledger, lines::add);
        var message = new CommandMessage(
            "settlements",
            "BTC/BRL",
            "8=FIX.4.4|35=U2|49=engine|56=ledger|17=settle-buy-1-1|55=BTC/BRL|54=2|44=100|38=10|10001=seller-A|10002=buyer-A|"
        );

        handler.handle(message);
        handler.handle(message);

        assertEquals(1, ledger.settlements().size());
        assertEquals(2, lines.size());
        assertEquals("ACCEPTED key=BTC/BRL command=TradeSettlementCommand", lines.getLast());
    }

    @Test
    void rejectsInvalidSettlementMessage() {
        var ledger = new RecordingLedger();
        var lines = new ArrayList<String>();
        var handler = new LedgerSettlementHandler(ledger, lines::add);

        handler.handle(new CommandMessage(
            "settlements",
            "BTC/BRL",
            "8=FIX.4.4|35=U2|49=engine|56=ledger|17=settle-buy-1-1|55=BTC/BRL|54=9|44=100|38=10|10001=seller-A|10002=buyer-A|"
        ));

        assertEquals("REJECTED key=BTC/BRL reason=Invalid MakerSide(54): 9", lines.getFirst());
    }

    private static final class RecordingLedger implements Ledger {

        private final List<RecordedSettlement> settlements = new ArrayList<>();
        private final List<RecordedRelease> releases = new ArrayList<>();

        @Override
        public void credit(AccountId accountId, Asset asset, long amount) {
            throw unsupported();
        }

        @Override
        public boolean creditFunding(String clientOrderId, AccountId accountId, Asset asset, long amount) {
            throw unsupported();
        }

        @Override
        public boolean debitFunding(String id, AccountId account, Asset asset, long amount) {
            throw new UnsupportedOperationException("not used by settlement tests");
        }

        @Override
        public void debitAvailable(AccountId accountId, Asset asset, long amount) {
            throw unsupported();
        }

        @Override
        public void reserve(AccountId accountId, Asset asset, long amount) {
            throw unsupported();
        }

        @Override
        public void release(AccountId accountId, Asset asset, long amount) {
            throw unsupported();
        }

        @Override
        public void settle(TradeSettlementInstruction instruction) {
            throw unsupported();
        }

        @Override
        public boolean settleExecution(String executionId, TradeSettlementInstruction instruction) {
            if (settlements.stream().anyMatch(settlement -> settlement.executionId().equals(executionId))) {
                return false;
            }
            settlements.add(new RecordedSettlement(executionId, instruction));
            return true;
        }

        @Override
        public boolean releaseExecution(String executionId, AccountId accountId, Asset asset, long amount) {
            if (releases.stream().anyMatch(release -> release.executionId().equals(executionId))) {
                return false;
            }
            releases.add(new RecordedRelease(executionId, accountId, asset, amount));
            return true;
        }

        @Override
        public AssetBalance balanceOf(AccountId accountId, Asset asset) {
            throw unsupported();
        }

        private List<RecordedSettlement> settlements() {
            return settlements;
        }

        private List<RecordedRelease> releases() {
            return releases;
        }

        private static LedgerException unsupported() {
            return new LedgerException("unsupported");
        }
    }

    private record RecordedSettlement(String executionId, TradeSettlementInstruction instruction) {
    }

    private record RecordedRelease(String executionId, AccountId accountId, Asset asset, long amount) {
    }
}
