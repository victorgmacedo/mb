package br.com.mb.engine.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.engine.command.NewOrderSingleCommand;
import br.com.mb.engine.command.OrderSide;
import br.com.mb.engine.domain.EngineState;
import br.com.mb.engine.domain.InstrumentCatalog;
import br.com.mb.engine.domain.OrderIntake;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class BookQueryServerTest {

    @Test
    void exposesEmptyRecoveredAndRepublishedBooksWithoutReadingMutableState() throws Exception {
        var state = new EngineState();
        var views = new BookViews();
        views.publish(state);
        var empty = views.find("BTC/BRL").orElseThrow();
        assertTrue(empty.contains("\"asks\":[]"));
        var intake = new OrderIntake(InstrumentCatalog.defaultCatalog());
        state.place(intake.accept(new NewOrderSingleCommand("seller", "s1", "BTC/BRL", OrderSide.SELL, 100, 2)));
        assertEquals(empty, views.find("BTC/BRL").orElseThrow());
        views.publish(state);
        assertTrue(views.find("BTC/BRL").orElseThrow().contains("\"remainingQuantity\":2"));
        try (var server = new BookQueryServer("127.0.0.1", 0, views); var client = HttpClient.newHttpClient()) {
            server.start();
            var base = "http://127.0.0.1:" + server.port();
            assertEquals(200, get(client, base + "/books?instrument=BTC%2FBRL").statusCode());
            assertEquals(404, get(client, base + "/books?instrument=DOGE%2FBRL").statusCode());
            assertEquals(400, get(client, base + "/books").statusCode());
            assertEquals(404, get(client, base + "/books/other?instrument=BTC").statusCode());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
