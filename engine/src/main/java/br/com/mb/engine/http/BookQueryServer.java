package br.com.mb.engine.http;

import br.com.mb.engine.book.BookPriceLevelView;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.shared.http.HttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Reads copied book views and balances under the command handler's monitor. */
public final class BookQueryServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final EngineCommandHandler commands;

    public BookQueryServer(String host, int port, EngineCommandHandler commands) throws IOException {
        this.commands = commands;
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);
        server.createContext("/books", this::handle);
        server.createContext("/accounts/", this::handle);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            HttpSupport.error(exchange, 405, "method_not_allowed");
            return;
        }
        try {
            var path = exchange.getRequestURI().getRawPath();
            if (path.equals("/books")) {
                var view = commands.book(HttpSupport.parameter(exchange, "instrument"));
                if (view.isEmpty()) HttpSupport.error(exchange, 404, "unknown_instrument");
                else {
                    var book = view.orElseThrow();
                    HttpSupport.respond(exchange, 200, "{\"instrument\":" + HttpSupport.quote(book.instrument())
                        + ",\"bids\":" + levels(book.bids()) + ",\"asks\":" + levels(book.asks()) + "}");
                }
            } else {
                var parts = path.split("/", -1);
                if (parts.length != 4 || !parts[1].equals("accounts") || !parts[3].equals("balances")) {
                    HttpSupport.error(exchange, 404, "not_found");
                    return;
                }
                var account = HttpSupport.decode(parts[2].replace("+", "%2B"));
                var asset = HttpSupport.parameter(exchange, "asset");
                var balance = commands.balanceOf(account, asset);
                HttpSupport.respond(exchange, 200, "{\"accountId\":" + HttpSupport.quote(account)
                    + ",\"asset\":" + HttpSupport.quote(asset) + ",\"available\":" + balance.available()
                    + ",\"locked\":" + balance.locked() + ",\"total\":"
                    + Math.addExact(balance.available(), balance.locked()) + "}");
            }
        } catch (IllegalArgumentException exception) {
            HttpSupport.error(exchange, 400, exception.getMessage());
        }
    }

    private static String levels(List<BookPriceLevelView> levels) {
        return levels.stream().map(level -> "{\"price\":" + level.price() + ",\"orders\":"
            + level.orders().stream().map(order -> "{\"accountId\":" + HttpSupport.quote(order.accountId())
                + ",\"clientOrderId\":" + HttpSupport.quote(order.clientOrderId())
                + ",\"remainingQuantity\":" + order.remainingQuantity()
                + ",\"entrySequence\":" + order.entrySequence()
                + ",\"enteredAt\":" + HttpSupport.quote(order.enteredAt().toString()) + "}")
                .collect(Collectors.joining(",", "[", "]")) + "}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override
    public void close() { server.stop(0); executor.close(); }
}
