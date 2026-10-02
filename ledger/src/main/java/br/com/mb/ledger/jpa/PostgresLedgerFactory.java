package br.com.mb.ledger.jpa;

import br.com.mb.ledger.domain.Ledger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class PostgresLedgerFactory {

    private PostgresLedgerFactory() {
    }

    public static Ledger create() {
        var context = new AnnotationConfigApplicationContext(PostgresLedgerConfig.class);
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        return context.getBean(Ledger.class);
    }
}
