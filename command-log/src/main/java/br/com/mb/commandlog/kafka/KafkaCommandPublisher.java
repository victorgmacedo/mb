package br.com.mb.commandlog.kafka;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaCommandPublisher implements CommandPublisher {

    private final Producer<String, String> producer;

    public KafkaCommandPublisher(Producer<String, String> producer) {
        this.producer = Objects.requireNonNull(producer, "producer must not be null");
    }

    public static KafkaCommandPublisher connect(String bootstrapServers, String clientId) {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        return new KafkaCommandPublisher(new KafkaProducer<>(properties));
    }

    @Override
    public void publish(CommandMessage message) {
        try {
            producer.send(new ProducerRecord<>(message.topic(), message.key(), message.value())).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommandPublishException("Interrupted while publishing command", exception);
        } catch (ExecutionException exception) {
            throw new CommandPublishException("Could not publish command", exception);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
