package br.com.mb.ledger.jooq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.model.Asset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MB_LEDGER_TEST_JDBC_URL", matches = ".+")
class JooqLedgerTest {

    @Test
    void preservesTransactionsDeduplicationAndConcurrentLocksOnPostgres() throws Exception {
        var baseUrl = System.getenv("MB_LEDGER_TEST_JDBC_URL");
        var user = System.getenv().getOrDefault("MB_LEDGER_USERNAME", "mb");
        var password = System.getenv().getOrDefault("MB_LEDGER_PASSWORD", "mb");
        var schema = "ledger_test_" + UUID.randomUUID().toString().replace("-", "");
        var url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (var statement = JooqDatabase.open(baseUrl, user, password)) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                var ledger = new JooqLedger(url, user, password);
                ledger.initialize();
                var buyer = new AccountId("buyer");
                var seller = new AccountId("seller");
                var base = new Asset("BTC");
                var quote = new Asset("BRL");
                assertEquals(AssetBalance.zero(), ledger.balanceOf(buyer, quote));
                assertTrue(ledger.creditFunding("fund", buyer, quote, 1_100));
                assertFalse(new JooqLedger(url, user, password).creditFunding("fund", buyer, quote, 1_100));
                assertThrows(LedgerException.class, () -> ledger.creditFunding("fund", buyer, quote, 1_101));
                ledger.reserve(buyer, quote, 1_000);
                assertThrows(LedgerException.class, () -> ledger.debitFunding("retry", buyer, quote, 101));
                assertTrue(ledger.debitFunding("retry", buyer, quote, 50)); // failed attempt rolled back deduplication
                assertFalse(ledger.debitFunding("retry", buyer, quote, 50));
                assertThrows(LedgerException.class, () -> ledger.debitFunding("retry", buyer, quote, 51));
                ledger.credit(seller, base, 10);
                var trade = new TradeSettlementInstruction(seller, buyer, SettlementSide.SELL, base, quote, 100, 10);
                assertThrows(LedgerException.class, () -> ledger.settleExecution("trade", trade));
                assertEquals(new AssetBalance(50, 1_000), ledger.balanceOf(buyer, quote));
                ledger.reserve(seller, base, 10);
                assertTrue(ledger.settleExecution("trade", trade));
                assertFalse(ledger.settleExecution("trade", trade));
                assertEquals(new AssetBalance(10, 0), ledger.balanceOf(buyer, base));
                assertEquals(new AssetBalance(1_000, 0), ledger.balanceOf(seller, quote));
                assertEquals(new AssetBalance(50, 0), ledger.balanceOf(buyer, quote));
                try (var workers = Executors.newFixedThreadPool(4)) {
                    Callable<Boolean> once = () -> new JooqLedger(url, user, password).debitFunding("concurrent", seller, quote, 100);
                    var results = workers.invokeAll(List.of(once, once, once, once));
                    var applied = 0;
                    for (var result : results) if (result.get()) applied++;
                    assertEquals(1, applied);
                    var reserves = workers.invokeAll(List.<Callable<Boolean>>of(
                        () -> { ledger.reserve(seller, quote, 100); return true; },
                        () -> { ledger.reserve(seller, quote, 100); return true; }));
                    for (var result : reserves) assertTrue(result.get());
                }
                assertEquals(new AssetBalance(700, 200), ledger.balanceOf(seller, quote));
                assertTrue(ledger.releaseExecution("release", seller, quote, 200));
                assertFalse(ledger.releaseExecution("release", seller, quote, 200));
                assertEquals(new AssetBalance(900, 0), ledger.balanceOf(seller, quote));
                var c = new AccountId("C");
                var d = new AccountId("D");
                for (var account : List.of(c, d)) {
                    ledger.credit(account, base, 10);
                    ledger.credit(account, quote, 1_000);
                    ledger.reserve(account, base, 10);
                    ledger.reserve(account, quote, 1_000);
                }
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var futures = workers.invokeAll(List.<Callable<Boolean>>of(
                        () -> ledger.settleExecution("C-to-D", new TradeSettlementInstruction(c, d, SettlementSide.SELL, base, quote, 100, 5)),
                        () -> ledger.settleExecution("D-to-C", new TradeSettlementInstruction(d, c, SettlementSide.SELL, base, quote, 100, 5))));
                    for (var result : futures) assertTrue(result.get());
                }
                for (var account : List.of(c, d)) {
                    assertEquals(new AssetBalance(5, 5), ledger.balanceOf(account, base));
                    assertEquals(new AssetBalance(500, 500), ledger.balanceOf(account, quote));
                }
                // Existing JPA funding records without a hash remain compatible.
                statement.execute("INSERT INTO " + schema + ".processed_commands(command_type,client_order_id,account_id,asset_symbol,amount) VALUES('FUNDING_CREDIT','legacy','buyer','BRL',50)");
                assertFalse(ledger.creditFunding("legacy", buyer, quote, 50));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
