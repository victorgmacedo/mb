package br.com.mb.engine.http;

import static org.junit.jupiter.api.Assertions.*;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.ledger.memory.InMemoryLedger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class BookQueryServerTest {
    @Test
    void servesTheSameInMemoryBalancesAndBooksUsedByCommands() throws Exception {
        var handler = new EngineCommandHandler(new CommandPublisher() {
            public void publish(CommandMessage message) {}
            public void close() {}
        }, "events", ignored -> {}, new InMemoryLedger());
        handler.classify(new CommandMessage("commands", "BTC", "8=FIX.4.4|35=U1|1=seller|11=fund|55=BTC|38=2|"));
        handler.classify(new CommandMessage("commands", "BTC/BRL", "8=FIX.4.4|35=D|1=seller|11=s1|55=BTC/BRL|54=2|44=100|38=2|"));
        var before = handler.book("BTC/BRL").orElseThrow();
        try (var server = new BookQueryServer("127.0.0.1", 0, handler); var client = HttpClient.newHttpClient()) {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            assertTrue(get(client, base + "/books?instrument=BTC%2FBRL").body().contains("\"remainingQuantity\":2"));
            assertTrue(get(client, base + "/accounts/seller/balances?asset=BTC").body().contains("\"locked\":2"));
            assertTrue(get(client, base + "/accounts/unknown/balances?asset=BRL").body().contains("\"total\":0"));
            assertEquals(404, get(client, base + "/books?instrument=UNKNOWN").statusCode());
            assertEquals(400, get(client, base + "/books").statusCode());
            assertEquals(400, get(client, base + "/accounts/seller/balances").statusCode());
            assertEquals(404, get(client, base + "/books/other?instrument=BTC").statusCode());
            assertEquals(404, get(client, base + "/accounts/seller/wrong?asset=BTC").statusCode());
            assertEquals(400, get(client, base + "/accounts/%20/balances?asset=BTC").statusCode());
            handler.classify(new CommandMessage("commands", "BTC/BRL", "8=FIX.4.4|35=F|1=seller|11=c1|41=s1|55=BTC/BRL|"));
            assertTrue(get(client, base + "/books?instrument=BTC%2FBRL").body().contains("\"asks\":[]"));
            assertEquals(1, before.asks().size()); // Previously returned views remain immutable.
            assertTrue(get(client, base + "/accounts/seller/balances?asset=BTC").body().contains("\"available\":2"));
        }
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
