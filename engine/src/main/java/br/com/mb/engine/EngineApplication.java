package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.commandlog.kafka.KafkaCommandPublisher;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;
import br.com.mb.engine.http.BookQueryServer;
import br.com.mb.ledger.memory.InMemoryLedger;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class EngineApplication {
    private EngineApplication() {}

    public static void main(String[] args) throws IOException {
        var environment = System.getenv();
        var config = EngineConfig.fromEnvironment(environment);
        var stopped = new CountDownLatch(1);
        var session = "mb-engine-" + UUID.randomUUID();
        try (var consumer = KafkaCommandConsumer.connect(config.bootstrapServers(), session, config.commandsTopic());
             var publisher = KafkaCommandPublisher.connect(config.bootstrapServers(), "mb-engine")) {
            var handler = new EngineCommandHandler(publisher, config.eventsTopic(), System.out::println, new InMemoryLedger());
            try (var queries = new BookQueryServer(environment.getOrDefault("ENGINE_HOST", "127.0.0.1"),
                    Integer.parseInt(environment.getOrDefault("ENGINE_PORT", "8081")), handler)) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    consumer.requestStop();
                    try { stopped.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                }));
                queries.start();
                System.out.println("Engine ready; books and balances start empty. Kafka session: " + session);
                while (!consumer.isStopping() && !Thread.currentThread().isInterrupted()) {
                    try { consumer.poll(handler); }
                    catch (RuntimeException exception) {
                        System.err.println("Command batch failed; retrying: " + exception.getMessage());
                        try { Thread.sleep(1_000); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }
            }
        } finally { stopped.countDown(); }
    }
}
