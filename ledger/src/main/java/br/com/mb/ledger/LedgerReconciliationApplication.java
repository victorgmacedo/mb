package br.com.mb.ledger;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.ledger.jooq.PostgresLedgerFactory;
import br.com.mb.ledger.settlement.LedgerSettlementHandler;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.logging.StructuredLogger;

/** Replays durable pending instructions independently of Kafka delivery. */
public final class LedgerReconciliationApplication {
    private static final StructuredLogger LOG = StructuredLogger.forClass(LedgerReconciliationApplication.class);
    private LedgerReconciliationApplication() {}

    public static void main(String[] args) throws InterruptedException {
        var ledger = PostgresLedgerFactory.create(System.getenv());
        var handler = new LedgerSettlementHandler(ledger, line -> LOG.info("ledger.reconciliation.result", "result", line));
        var once = java.util.List.of(args).contains("--once");
        do {
            for (var payload : ledger.pendingInstructions(100)) {
                var fix = FixMessage.parse(payload);
                // A failure stops the batch rather than skipping an unresolved financial instruction.
                try {
                    handler.handle(new CommandMessage("settlements", fix.kafkaKey(), payload));
                } catch (br.com.mb.ledger.domain.LedgerException exception) {
                    if (once) throw exception;
                    LOG.info("ledger.reconciliation.retry", "reason", exception.getMessage());
                    break;
                }
            }
            if (!once) Thread.sleep(1_000);
        } while (!once && !Thread.currentThread().isInterrupted());
    }
}
