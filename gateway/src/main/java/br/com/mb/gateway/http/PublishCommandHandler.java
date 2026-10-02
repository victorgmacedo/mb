package br.com.mb.gateway.http;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class PublishCommandHandler implements HttpHandler {

    private final String commandsTopic;
    private final CommandPublisher publisher;

    PublishCommandHandler(String commandsTopic, CommandPublisher publisher) {
        this.commandsTopic = Objects.requireNonNull(commandsTopic, "commandsTopic must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "method_not_allowed\n");
            return;
        }

        var rawFixMessage = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        FixMessage fixMessage;
        try {
            fixMessage = FixMessage.parse(rawFixMessage);
        } catch (InvalidFixMessageException exception) {
            respond(exchange, 400, "invalid_fix_message: %s\n".formatted(exception.getMessage()));
            return;
        }

        publisher.publish(new CommandMessage(commandsTopic, fixMessage.kafkaKey(), fixMessage.normalized()));
        respond(exchange, 202, "accepted\n");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }
}
