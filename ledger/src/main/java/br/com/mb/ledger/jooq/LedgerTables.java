package br.com.mb.ledger.jooq;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Typed SQL identifiers for the existing ledger schema. */
final class LedgerTables {
    static final Table<Record> BALANCES = DSL.table(DSL.name("ledger_balances"));
    static final Table<Record> COMMANDS = DSL.table(DSL.name("processed_commands"));
    static final Field<String> ACCOUNT = DSL.field(DSL.name("account_id"), String.class);
    static final Field<String> ASSET = DSL.field(DSL.name("asset_symbol"), String.class);
    static final Field<Long> AVAILABLE = DSL.field(DSL.name("available"), Long.class);
    static final Field<Long> LOCKED = DSL.field(DSL.name("locked"), Long.class);
    static final Field<Long> VERSION = DSL.field(DSL.name("version"), Long.class);
    static final Field<String> TYPE = DSL.field(DSL.name("command_type"), String.class);
    static final Field<String> COMMAND_ID = DSL.field(DSL.name("client_order_id"), String.class);
    static final Field<Long> AMOUNT = DSL.field(DSL.name("amount"), Long.class);
    static final Field<String> HASH = DSL.field(DSL.name("payload_hash"), String.class);

    private LedgerTables() {}
}
