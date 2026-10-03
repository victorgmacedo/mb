package br.com.mb.ledger.jdbc;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.ledger.domain.TradeSettlementInstruction;
import br.com.mb.shared.model.Asset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** PostgreSQL ledger with explicit transactions, stable row locks and durable command deduplication. */
public final class JdbcLedger implements Ledger {

    private final String url;
    private final String username;
    private final String password;

    public JdbcLedger(String url, String username, String password) {
        this.url = Objects.requireNonNull(url);
        this.username = Objects.requireNonNull(username);
        this.password = Objects.requireNonNull(password);
    }

    public void initialize() {
        transaction(connection -> {
            try (var input = JdbcLedger.class.getResourceAsStream("/ledger-schema.sql")) {
                if (input == null) throw new IllegalStateException("ledger-schema.sql missing");
                var schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                try (var statement = connection.createStatement()) {
                    // Processes may initialize the same schema simultaneously.
                    statement.execute("SELECT pg_advisory_xact_lock(724813521)");
                    for (var sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            } catch (IOException exception) {
                throw new LedgerStorageException("Could not read ledger schema", exception);
            }
            return null;
        });
    }

    @Override
    public void credit(AccountId account, Asset asset, long amount) {
        change(account, asset, amount, balance -> balance.credit(amount));
    }

    @Override
    public boolean creditFunding(String id, AccountId account, Asset asset, long amount) {
        return funding("FUNDING_CREDIT", id, account, asset, amount, balance -> balance.credit(amount));
    }

    @Override
    public boolean debitFunding(String id, AccountId account, Asset asset, long amount) {
        return funding("FUNDING_DEBIT", id, account, asset, amount, balance -> balance.debitAvailable(amount));
    }

    @Override
    public void debitAvailable(AccountId account, Asset asset, long amount) {
        change(account, asset, amount, balance -> balance.debitAvailable(amount));
    }

    @Override
    public void reserve(AccountId account, Asset asset, long amount) {
        change(account, asset, amount, balance -> balance.reserve(amount));
    }

    @Override
    public void release(AccountId account, Asset asset, long amount) {
        change(account, asset, amount, balance -> balance.release(amount));
    }

    @Override
    public void settle(TradeSettlementInstruction instruction) {
        Objects.requireNonNull(instruction);
        transaction(connection -> { settle(connection, instruction); return null; });
    }

    @Override
    public boolean settleExecution(String id, TradeSettlementInstruction instruction) {
        Objects.requireNonNull(instruction);
        return transaction(connection -> {
            if (!record(connection, "TRADE_SETTLEMENT", id, instruction.makerAccountId().value(),
                instruction.baseAsset().symbol() + "/" + instruction.quoteAsset().symbol(), instruction.quantity(),
                "TRADE_SETTLEMENT|%s|%s|%s|%s|%s|%d|%d".formatted(instruction.makerAccountId().value(),
                    instruction.takerAccountId().value(), instruction.makerSide(), instruction.baseAsset().symbol(),
                    instruction.quoteAsset().symbol(), instruction.price(), instruction.quantity()))) return false;
            settle(connection, instruction);
            return true;
        });
    }

    @Override
    public boolean releaseExecution(String id, AccountId account, Asset asset, long amount) {
        requirePositive(amount);
        var key = new BalanceKey(account, asset);
        return transaction(connection -> {
            if (!record(connection, "BALANCE_RELEASE", id, account.value(), asset.symbol(), amount,
                "BALANCE_RELEASE|%s|%s|%d".formatted(account.value(), asset.symbol(), amount))) return false;
            mutate(connection, List.of(key), rows -> rows.get(key).release(amount));
            return true;
        });
    }

    @Override
    public AssetBalance balanceOf(AccountId account, Asset asset) {
        var key = new BalanceKey(account, asset);
        return transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT available, locked FROM ledger_balances WHERE account_id=? AND asset_symbol=?")) {
                query.setString(1, key.account().value());
                query.setString(2, key.asset().symbol());
                try (var rows = query.executeQuery()) {
                    return rows.next() ? new AssetBalance(rows.getLong(1), rows.getLong(2)) : AssetBalance.zero();
                }
            }
        });
    }

    private void change(AccountId account, Asset asset, long amount, Consumer<LedgerBalance> operation) {
        requirePositive(amount);
        var key = new BalanceKey(account, asset);
        transaction(connection -> { mutate(connection, List.of(key), rows -> operation.accept(rows.get(key))); return null; });
    }

    private boolean funding(String type, String id, AccountId account, Asset asset, long amount, Consumer<LedgerBalance> operation) {
        requirePositive(amount);
        var key = new BalanceKey(account, asset);
        return transaction(connection -> {
            if (!record(connection, type, id, account.value(), asset.symbol(), amount,
                "%s|%s|%s|%d".formatted(type, account.value(), asset.symbol(), amount))) return false;
            mutate(connection, List.of(key), rows -> operation.accept(rows.get(key)));
            return true;
        });
    }

    private void settle(Connection connection, TradeSettlementInstruction trade) throws SQLException {
        var makerBase = new BalanceKey(trade.makerAccountId(), trade.baseAsset());
        var makerQuote = new BalanceKey(trade.makerAccountId(), trade.quoteAsset());
        var takerBase = new BalanceKey(trade.takerAccountId(), trade.baseAsset());
        var takerQuote = new BalanceKey(trade.takerAccountId(), trade.quoteAsset());
        var quoteAmount = trade.quoteAmount();
        mutate(connection, List.of(makerBase, makerQuote, takerBase, takerQuote), rows -> {
            var baseSeller = rows.get(trade.makerSide() == SettlementSide.SELL ? makerBase : takerBase);
            var quoteBuyer = rows.get(trade.makerSide() == SettlementSide.SELL ? takerQuote : makerQuote);
            var baseBuyer = rows.get(trade.makerSide() == SettlementSide.SELL ? takerBase : makerBase);
            var quoteSeller = rows.get(trade.makerSide() == SettlementSide.SELL ? makerQuote : takerQuote);
            baseSeller.requireLocked(trade.quantity());
            quoteBuyer.requireLocked(quoteAmount);
            baseBuyer.requireCanCredit(trade.quantity());
            quoteSeller.requireCanCredit(quoteAmount);
            baseSeller.debitLocked(trade.quantity());
            quoteBuyer.debitLocked(quoteAmount);
            baseBuyer.credit(trade.quantity());
            quoteSeller.credit(quoteAmount);
        });
    }

    private void mutate(Connection connection, List<BalanceKey> keys, Consumer<Map<BalanceKey, LedgerBalance>> operation) throws SQLException {
        var sorted = keys.stream().distinct().sorted(Comparator.comparing((BalanceKey key) -> key.account().value())
            .thenComparing(key -> key.asset().symbol())).toList();
        var balances = new HashMap<BalanceKey, LedgerBalance>();
        for (var key : sorted) {
            try (var insert = connection.prepareStatement("INSERT INTO ledger_balances(account_id,asset_symbol,available,locked,version) VALUES(?,?,0,0,0) ON CONFLICT(account_id,asset_symbol) DO NOTHING")) {
                insert.setString(1, key.account().value()); insert.setString(2, key.asset().symbol()); insert.executeUpdate();
            }
            try (var query = connection.prepareStatement("SELECT available,locked FROM ledger_balances WHERE account_id=? AND asset_symbol=? FOR UPDATE")) {
                query.setString(1, key.account().value()); query.setString(2, key.asset().symbol());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("balance missing after insert");
                    balances.put(key, new LedgerBalance(key.account().value(), key.asset().symbol(), rows.getLong(1), rows.getLong(2)));
                }
            }
        }
        operation.accept(balances);
        for (var key : sorted) {
            var balance = balances.get(key).toBalance();
            try (var update = connection.prepareStatement("UPDATE ledger_balances SET available=?,locked=?,version=version+1 WHERE account_id=? AND asset_symbol=?")) {
                update.setLong(1, balance.available()); update.setLong(2, balance.locked());
                update.setString(3, key.account().value()); update.setString(4, key.asset().symbol());
                if (update.executeUpdate() != 1) throw new SQLException("balance update failed");
            }
        }
    }

    private boolean record(Connection connection, String type, String id, String account, String asset, long amount, String payload) throws SQLException {
        Objects.requireNonNull(id, "command id must not be null");
        if (id.isBlank()) throw new LedgerException("command id must not be blank");
        var hash = sha256(payload);
        try (var insert = connection.prepareStatement("INSERT INTO processed_commands(command_type,client_order_id,account_id,asset_symbol,amount,payload_hash) VALUES(?,?,?,?,?,?) ON CONFLICT(command_type,client_order_id) DO NOTHING")) {
            insert.setString(1,type); insert.setString(2,id); insert.setString(3,account); insert.setString(4,asset);
            insert.setLong(5,amount); insert.setString(6,hash);
            if (insert.executeUpdate() == 1) return true;
        }
        try (var query = connection.prepareStatement("SELECT account_id,asset_symbol,amount,payload_hash FROM processed_commands WHERE command_type=? AND client_order_id=?")) {
            query.setString(1,type); query.setString(2,id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("processed command missing after conflict");
                // Older credit records may lack a hash; retain their persisted payload semantics.
                var matches = type.equals("FUNDING_CREDIT")
                    ? account.equals(rows.getString(1)) && asset.equals(rows.getString(2)) && amount == rows.getLong(3)
                    : hash.equals(rows.getString(4));
                if (!matches) throw new LedgerException(type.equals("FUNDING_CREDIT")
                    ? "conflicting duplicate funding command" : "conflicting duplicate ledger command");
                return false;
            }
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                var result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new LedgerStorageException("Ledger PostgreSQL transaction failed", exception);
        }
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new LedgerException("amount must be positive");
    }

    private record BalanceKey(AccountId account, Asset asset) {
        private BalanceKey { Objects.requireNonNull(account); Objects.requireNonNull(asset); }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
