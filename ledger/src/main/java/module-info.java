module br.com.mb.ledger {
    requires br.com.mb.commandlog;
    requires br.com.mb.shared;
    requires jakarta.persistence;
    requires java.sql;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.data.commons;
    requires spring.data.jpa;
    requires spring.jdbc;
    requires spring.orm;
    requires spring.tx;

    exports br.com.mb.ledger;
    exports br.com.mb.ledger.domain;
    exports br.com.mb.ledger.jpa;

    opens br.com.mb.ledger.jpa to org.hibernate.orm.core, spring.beans, spring.context, spring.core, spring.data.commons, spring.data.jpa;
}
