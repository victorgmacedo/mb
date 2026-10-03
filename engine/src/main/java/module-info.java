module br.com.mb.engine {
    requires br.com.mb.commandlog;
    requires br.com.mb.ledger;
    requires br.com.mb.shared;
    requires com.google.protobuf;
    requires java.sql;
    requires jdk.httpserver;

    exports br.com.mb.engine.http;

    exports br.com.mb.engine;
    exports br.com.mb.engine.book;
    exports br.com.mb.engine.config;
    exports br.com.mb.engine.command;
    exports br.com.mb.engine.domain;
    exports br.com.mb.engine.journal;
    exports br.com.mb.engine.snapshot;
}
