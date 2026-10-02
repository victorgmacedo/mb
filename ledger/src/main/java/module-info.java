module br.com.mb.ledger {
    requires br.com.mb.commandlog;
    requires br.com.mb.shared;
    requires jakarta.persistence;
    requires java.sql;
    requires org.hibernate.orm.core;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.data.commons;
    requires spring.data.jpa;
    requires spring.jdbc;
    requires spring.orm;
    requires spring.tx;

    exports br.com.mb.ledger;
    exports br.com.mb.ledger.config;
    exports br.com.mb.ledger.domain;
    exports br.com.mb.ledger.jpa;
    exports br.com.mb.ledger.settlement;

    opens br.com.mb.ledger.jpa;
}
