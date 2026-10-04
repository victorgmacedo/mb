package br.com.mb.gateway.http;

import br.com.mb.shared.http.HttpSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class QueryHandler implements HttpHandler {
    private final URI engineUrl;
    private final HttpClient client;

    QueryHandler(URI engineUrl, HttpClient client) {
        this.engineUrl = engineUrl;
        this.client = client;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            HttpSupport.error(exchange, 405, "method_not_allowed");
            return;
        }
        try {
            var path = exchange.getRequestURI().getRawPath();
            if (path.equals("/books")) HttpSupport.parameter(exchange, "instrument");
            else {
                var parts = path.split("/", -1);
                if (parts.length != 4 || !parts[1].equals("accounts") || !parts[3].equals("balances")) {
                    HttpSupport.error(exchange, 404, "not_found");
                    return;
                }
                HttpSupport.parameter(exchange, "asset");
            }
            var uri = engineUrl.resolve(path + "?" + exchange.getRequestURI().getRawQuery());
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 || response.statusCode() == 400 || response.statusCode() == 404) {
                HttpSupport.respond(exchange, response.statusCode(), response.body());
            } else HttpSupport.error(exchange, 503, "engine_unavailable");
        } catch (IllegalArgumentException exception) {
            HttpSupport.error(exchange, 400, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            HttpSupport.error(exchange, 503, "query_unavailable");
        } catch (IOException | RuntimeException exception) {
            HttpSupport.error(exchange, 503, "query_unavailable");
        }
    }
}
