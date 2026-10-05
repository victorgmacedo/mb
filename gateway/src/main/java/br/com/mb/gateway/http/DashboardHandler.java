package br.com.mb.gateway.http;

import br.com.mb.shared.http.HttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/** Serves the dashboard's fixed classpath resources on the gateway origin. */
final class DashboardHandler implements HttpHandler {
    private static final Map<String, String> FILES = Map.of(
        "/", "index.html", "/app.js", "app.js", "/style.css", "style.css");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            HttpSupport.error(exchange, 405, "method_not_allowed");
            return;
        }
        var file = FILES.get(exchange.getRequestURI().getPath());
        if (file == null) {
            HttpSupport.error(exchange, 404, "not_found");
            return;
        }
        try (var resource = DashboardHandler.class.getResourceAsStream("/dashboard/" + file)) {
            if (resource == null) {
                HttpSupport.error(exchange, 404, "not_found");
                return;
            }
            var bytes = resource.readAllBytes();
            var type = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var response = exchange.getResponseBody()) { response.write(bytes); }
        }
    }
}
