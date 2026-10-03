module br.com.mb.ledger {
    requires br.com.mb.commandlog;
    requires br.com.mb.shared;
    requires java.sql;

    exports br.com.mb.ledger;
    exports br.com.mb.ledger.config;
    exports br.com.mb.ledger.domain;
    exports br.com.mb.ledger.jdbc;
    exports br.com.mb.ledger.settlement;
}
