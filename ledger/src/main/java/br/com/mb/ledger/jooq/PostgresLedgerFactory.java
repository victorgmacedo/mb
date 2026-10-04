package br.com.mb.ledger.jooq;

import br.com.mb.ledger.domain.Ledger;
import java.util.Map;

public final class PostgresLedgerFactory {

    private PostgresLedgerFactory() {
    }

    public static Ledger create() {
        return create(System.getenv());
    }

    public static JooqLedger create(Map<String, String> environment) {
        var ledger = new JooqLedger(environment.getOrDefault("MB_LEDGER_JDBC_URL", "jdbc:postgresql://localhost:5432/mb"),
            environment.getOrDefault("MB_LEDGER_USERNAME", "mb"), environment.getOrDefault("MB_LEDGER_PASSWORD", "mb"));
        if (Boolean.parseBoolean(environment.getOrDefault("MB_LEDGER_INITIALIZE_SCHEMA", "true"))) ledger.initialize();
        return ledger;
    }
}
