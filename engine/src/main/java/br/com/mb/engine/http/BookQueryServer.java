package br.com.mb.engine.http;

import br.com.mb.shared.http.HttpSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BookQueryServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BookQueryServer(String host, int port, BookViews views) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/books", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/books")) {
                HttpSupport.error(exchange, 404, "not_found");
            } else if (!exchange.getRequestMethod().equals("GET")) {
                HttpSupport.error(exchange, 405, "method_not_allowed");
            } else {
                try {
                    var book = views.find(HttpSupport.parameter(exchange, "instrument"));
                    if (book.isPresent()) HttpSupport.respond(exchange, 200, book.get());
                    else HttpSupport.error(exchange, 404, "unknown_instrument");
                } catch (IllegalArgumentException exception) {
                    HttpSupport.error(exchange, 400, exception.getMessage());
                }
            }
        });
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
