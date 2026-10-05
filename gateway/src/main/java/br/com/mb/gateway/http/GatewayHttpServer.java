package br.com.mb.gateway.http;

import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GatewayHttpServer {

    private final GatewayConfig config;
    private final CommandPublisher publisher;
    private HttpServer server;
    private final URI engineUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private ExecutorService executor;

    public GatewayHttpServer(GatewayConfig config, CommandPublisher publisher, URI engineUrl) {
        this.engineUrl = Objects.requireNonNull(engineUrl);
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.createContext("/", new DashboardHandler());
            server.createContext("/commands", new PublishCommandHandler(config.commandsTopic(), publisher));
            var queries = new QueryHandler(engineUrl, client);
            server.createContext("/accounts/", queries);
            server.createContext("/books", queries);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.start();
        } catch (IOException exception) {
            throw new GatewayStartException("Could not start gateway HTTP server", exception);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            executor.close();
            client.close();
        }
    }
}
