package br.com.mb.ledger.jooq;

import static br.com.mb.ledger.jooq.LedgerTables.ACCOUNT;
import static br.com.mb.ledger.jooq.LedgerTables.AMOUNT;
import static br.com.mb.ledger.jooq.LedgerTables.ASSET;
import static br.com.mb.ledger.jooq.LedgerTables.AVAILABLE;
import static br.com.mb.ledger.jooq.LedgerTables.BALANCES;
import static br.com.mb.ledger.jooq.LedgerTables.COMMAND_ID;
import static br.com.mb.ledger.jooq.LedgerTables.COMMANDS;
import static br.com.mb.ledger.jooq.LedgerTables.HASH;
import static br.com.mb.ledger.jooq.LedgerTables.LOCKED;
import static br.com.mb.ledger.jooq.LedgerTables.TYPE;
import static br.com.mb.ledger.jooq.LedgerTables.VERSION;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

/** PostgreSQL ledger with explicit transactions, stable row locks and durable command deduplication. */
public final class JooqLedger implements Ledger {

    private final String url;
    private final String username;
    private final String password;

    public JooqLedger(String url, String username, String password) {
        this.url = Objects.requireNonNull(url);
        this.username = Objects.requireNonNull(username);
        this.password = Objects.requireNonNull(password);
    }

    public void initialize() {
        transaction(connection -> {
            try (var input = JooqLedger.class.getResourceAsStream("/ledger-schema.sql")) {
                if (input == null) throw new IllegalStateException("ledger-schema.sql missing");
                var schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                connection.execute("SELECT pg_advisory_xact_lock(724813521)");
                for (var sql : schema.split(";")) if (!sql.isBlank()) connection.execute(sql);
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
            var row = connection.select(AVAILABLE, LOCKED).from(BALANCES)
                .where(ACCOUNT.eq(key.account().value()).and(ASSET.eq(key.asset().symbol()))).fetchOne();
            return row == null ? AssetBalance.zero() : new AssetBalance(row.get(AVAILABLE), row.get(LOCKED));
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

    private void settle(DSLContext connection, TradeSettlementInstruction trade) {
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

    private void mutate(DSLContext connection, List<BalanceKey> keys, Consumer<Map<BalanceKey, LedgerBalance>> operation) {
        var sorted = keys.stream().distinct().sorted(Comparator.comparing((BalanceKey key) -> key.account().value())
            .thenComparing(key -> key.asset().symbol())).toList();
        var balances = new HashMap<BalanceKey, LedgerBalance>();
        for (var key : sorted) {
            connection.insertInto(BALANCES, ACCOUNT, ASSET, AVAILABLE, LOCKED, VERSION)
                .values(key.account().value(), key.asset().symbol(), 0L, 0L, 0L)
                .onConflict(ACCOUNT, ASSET).doNothing().execute();
            var row = connection.select(AVAILABLE, LOCKED).from(BALANCES)
                .where(ACCOUNT.eq(key.account().value()).and(ASSET.eq(key.asset().symbol()))).forUpdate().fetchOne();
            if (row == null) throw new LedgerStorageException("balance missing after insert", null);
            balances.put(key, new LedgerBalance(key.account().value(), key.asset().symbol(), row.get(AVAILABLE), row.get(LOCKED)));
        }
        operation.accept(balances);
        for (var key : sorted) {
            var balance = balances.get(key).toBalance();
            if (connection.update(BALANCES).set(AVAILABLE, balance.available()).set(LOCKED, balance.locked())
                .set(VERSION, VERSION.add(1L)).where(ACCOUNT.eq(key.account().value()).and(ASSET.eq(key.asset().symbol()))).execute() != 1) {
                throw new LedgerStorageException("balance update failed", null);
            }
        }
    }

    private boolean record(DSLContext connection, String type, String id, String account, String asset, long amount, String payload) {
        Objects.requireNonNull(id, "command id must not be null");
        if (id.isBlank()) throw new LedgerException("command id must not be blank");
        var hash = sha256(payload);
        if (connection.insertInto(COMMANDS, TYPE, COMMAND_ID, ACCOUNT, ASSET, AMOUNT, HASH)
            .values(type, id, account, asset, amount, hash).onConflict(TYPE, COMMAND_ID).doNothing().execute() == 1) return true;
        var row = connection.select(ACCOUNT, ASSET, AMOUNT, HASH).from(COMMANDS)
            .where(TYPE.eq(type).and(COMMAND_ID.eq(id))).fetchOne();
        if (row == null) throw new LedgerStorageException("processed command missing after conflict", null);
        // Older credit records may lack a hash; retain their persisted payload semantics.
        var matches = type.equals("FUNDING_CREDIT")
            ? account.equals(row.get(ACCOUNT)) && asset.equals(row.get(ASSET)) && amount == row.get(AMOUNT)
            : hash.equals(row.get(HASH));
        if (!matches) throw new LedgerException(type.equals("FUNDING_CREDIT")
            ? "conflicting duplicate funding command" : "conflicting duplicate ledger command");
        return false;
    }

    private <T> T transaction(Function<DSLContext, T> work) {
        try (var database = JooqDatabase.open(url, username, password)) {
            return database.transactionResult(configuration -> {
                var context = configuration.dsl();
                context.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                return work.apply(context);
            });
        } catch (DataAccessException exception) {
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

}
