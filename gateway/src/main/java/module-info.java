module br.com.mb.gateway {
    requires br.com.mb.commandlog;
    requires br.com.mb.shared;
    requires jdk.httpserver;
    requires java.net.http;
    requires br.com.mb.ledger;

    exports br.com.mb.gateway;
}
