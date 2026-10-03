package br.com.mb.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import br.com.mb.ledger.domain.AssetBalance;
import br.com.mb.ledger.domain.Ledger;
import br.com.mb.shared.http.HttpSupport;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class GatewayHttpServerTest {

    @Test
    void servesBalancesProxiesBooksAndReportsPublicationFailures() throws Exception {
        var published = new ArrayList<CommandMessage>();
        var publisher = new CommandPublisher() {
            public void publish(CommandMessage message) {
                if (message.value().contains("11=fail")) throw new IllegalStateException("offline");
                published.add(message);
            }
            public void close() { }
        };
        var ledger = (Ledger) Proxy.newProxyInstance(Ledger.class.getClassLoader(), new Class<?>[] {Ledger.class},
            (proxy, method, args) -> {
                if (method.getName().equals("balanceOf")) return new AssetBalance(10, 4);
                throw new UnsupportedOperationException();
            });
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/books", exchange -> {
            var known = HttpSupport.parameter(exchange, "instrument").equals("BTC/BRL");
            HttpSupport.respond(exchange, known ? 200 : 404, known ? "{\"bids\":[],\"asks\":[]}" : "{}");
        });
        upstream.start();
        var gateway = new GatewayHttpServer(new GatewayConfig("127.0.0.1", 0, "unused", "commands"), publisher,
            ledger, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()));
        gateway.start();
        try (var client = HttpClient.newHttpClient()) {
            var base = "http://127.0.0.1:" + gateway.port();
            var balance = get(client, base + "/accounts/A/balances?asset=BRL");
            assertEquals(200, balance.statusCode());
            assertTrue(balance.body().contains("\"total\":14"));
            assertEquals(400, get(client, base + "/accounts/A/balances").statusCode());
            assertEquals(404, get(client, base + "/accounts/A/wrong?asset=BRL").statusCode());
            assertEquals(200, get(client, base + "/books?instrument=BTC%2FBRL").statusCode());
            assertEquals(404, get(client, base + "/books?instrument=UNKNOWN").statusCode());
            assertEquals(400, get(client, base + "/books?instrument=BTC&instrument=ETH").statusCode());
            assertEquals(405, get(client, base + "/commands").statusCode());
            assertEquals(202, post(client, base, "8=FIX.4.4|35=U6|49=gateway|11=ok|55=BRL|").statusCode());
            assertEquals("BRL", published.getFirst().key());
            assertEquals(400, post(client, base, "bad FIX").statusCode());
            assertEquals(503, post(client, base, "8=FIX.4.4|35=U6|49=gateway|11=fail|55=BRL|").statusCode());
            upstream.stop(0);
            assertEquals(503, get(client, base + "/books?instrument=BTC%2FBRL").statusCode());
        } finally {
            gateway.stop();
            upstream.stop(0);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String base, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/commands"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
