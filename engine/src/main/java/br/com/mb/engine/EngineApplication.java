package br.com.mb.engine;

import br.com.mb.commandlog.kafka.KafkaCommandConsumer;
import br.com.mb.engine.command.EngineCommandHandler;
import br.com.mb.engine.config.EngineConfig;

public final class EngineApplication {

    private EngineApplication() {
    }

    public static void main(String[] args) {
        var config = EngineConfig.fromEnvironment(System.getenv());
        var consumer = KafkaCommandConsumer.connect(
            config.bootstrapServers(),
            config.consumerGroupId(),
            config.commandsTopic()
        );
        var handler = new EngineCommandHandler(System.out::println);

        Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));

        while (!Thread.currentThread().isInterrupted()) {
            consumer.poll(handler);
        }
    }
}
