package br.com.mb.ledger.jooq;

import org.jooq.CloseableDSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/** Creates an operation-scoped context; jOOQ owns and closes its database connection. */
public final class JooqDatabase {
    private JooqDatabase() {}

    public static CloseableDSLContext open(String url, String username, String password) {
        var context = DSL.using(url, username, password);
        context.configuration().set(url.startsWith("jdbc:h2:") ? SQLDialect.H2 : SQLDialect.POSTGRES);
        context.configuration().set(new Settings().withExecuteLogging(false));
        return context;
    }
}
