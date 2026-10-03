package br.com.mb.gateway.http;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.shared.http.HttpSupport;
import br.com.mb.shared.model.Asset;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class QueryHandler implements HttpHandler {

    private final Ledger ledger;
    private final URI engineUrl;
    private final HttpClient client;

    QueryHandler(Ledger ledger, URI engineUrl, HttpClient client) {
        this.ledger = ledger;
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
            if (path.equals("/books")) {
                var instrument = HttpSupport.parameter(exchange, "instrument");
                var uri = engineUrl.resolve("/books?instrument=" + URLEncoder.encode(instrument, StandardCharsets.UTF_8));
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200 || response.statusCode() == 404) {
                    HttpSupport.respond(exchange, response.statusCode(), response.body());
                } else HttpSupport.error(exchange, 503, "engine_unavailable");
            } else {
                var parts = path.split("/", -1);
                if (parts.length != 4 || !parts[1].equals("accounts") || !parts[3].equals("balances")) {
                    HttpSupport.error(exchange, 404, "not_found");
                    return;
                }
                var account = new AccountId(HttpSupport.decode(parts[2].replace("+", "%2B")));
                var asset = new Asset(HttpSupport.parameter(exchange, "asset"));
                var balance = ledger.balanceOf(account, asset);
                HttpSupport.respond(exchange, 200, "{\"accountId\":" + HttpSupport.quote(account.value())
                    + ",\"asset\":" + HttpSupport.quote(asset.symbol()) + ",\"available\":" + balance.available()
                    + ",\"locked\":" + balance.locked() + ",\"total\":"
                    + Math.addExact(balance.available(), balance.locked()) + "}");
            }
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
