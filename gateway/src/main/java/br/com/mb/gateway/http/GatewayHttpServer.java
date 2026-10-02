package br.com.mb.gateway.http;

import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.gateway.config.GatewayConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;

public final class GatewayHttpServer {

    private final GatewayConfig config;
    private final CommandPublisher publisher;
    private HttpServer server;

    public GatewayHttpServer(GatewayConfig config, CommandPublisher publisher) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.createContext("/commands", new PublishCommandHandler(config.commandsTopic(), publisher));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        } catch (IOException exception) {
            throw new GatewayStartException("Could not start gateway HTTP server", exception);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
