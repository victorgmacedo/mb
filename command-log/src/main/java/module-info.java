module br.com.mb.commandlog {
    requires br.com.mb.shared;
    requires kafka.clients;

    exports br.com.mb.commandlog;
    exports br.com.mb.commandlog.kafka;
}
