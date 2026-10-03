package br.com.mb.shared.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/** Small HTTP helpers for the JDK servers; no reflection or external JSON dependency. */
public final class HttpSupport {

    private HttpSupport() {
    }

    public static String quote(String value) {
        var json = new StringBuilder("\"");
        for (var ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                default -> {
                    if (ch < 32) json.append("\\u%04x".formatted((int) ch));
                    else json.append(ch);
                }
            }
        }
        return json.append('"').toString();
    }

    public static String parameter(HttpExchange exchange, String name) {
        var values = new HashMap<String, String>();
        var query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (var part : query.split("&")) {
                var pair = part.split("=", 2);
                var key = decode(pair[0]);
                if (pair.length != 2 || values.putIfAbsent(key, decode(pair[1])) != null) {
                    throw new IllegalArgumentException("invalid or duplicate query parameter");
                }
            }
        }
        var value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing parameter: " + name);
        return value;
    }

    public static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public static void error(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, "{\"error\":" + quote(message) + "}");
    }
}
