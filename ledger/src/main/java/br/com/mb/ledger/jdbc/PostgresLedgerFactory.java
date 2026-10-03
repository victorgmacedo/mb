package br.com.mb.ledger.jdbc;

import br.com.mb.ledger.domain.Ledger;
import java.util.Map;

public final class PostgresLedgerFactory {

    private PostgresLedgerFactory() {
    }

    public static Ledger create() {
        return create(System.getenv());
    }

    public static JdbcLedger create(Map<String, String> environment) {
        var ledger = new JdbcLedger(environment.getOrDefault("MB_LEDGER_JDBC_URL", "jdbc:postgresql://localhost:5432/mb"),
            environment.getOrDefault("MB_LEDGER_USERNAME", "mb"), environment.getOrDefault("MB_LEDGER_PASSWORD", "mb"));
        if (Boolean.parseBoolean(environment.getOrDefault("MB_LEDGER_INITIALIZE_SCHEMA", "true"))) ledger.initialize();
        return ledger;
    }
}
