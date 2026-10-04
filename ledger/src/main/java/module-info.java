module br.com.mb.ledger {
    requires br.com.mb.commandlog;
    requires br.com.mb.shared;
    requires org.jooq;
    requires static jakarta.xml.bind;

    exports br.com.mb.ledger;
    exports br.com.mb.ledger.config;
    exports br.com.mb.ledger.domain;
    exports br.com.mb.ledger.jooq;
    exports br.com.mb.ledger.settlement;
}
