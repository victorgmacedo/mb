package br.com.mb.commandlog.kafka;

import br.com.mb.commandlog.CommandConsumer;
import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaCommandConsumer implements CommandConsumer {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private final Consumer<String, String> consumer;
    private final String topic;
    private volatile boolean stopping;

    public KafkaCommandConsumer(Consumer<String, String> consumer, String topic) {
        this.consumer = Objects.requireNonNull(consumer);
        this.topic = Objects.requireNonNull(topic);
        consumer.subscribe(List.of(topic));
    }

    /** A fresh session deliberately ignores old commands: books and balances do not survive restart. */
    public static KafkaCommandConsumer connect(String bootstrapServers, String groupId, String topic) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        var kafka = new KafkaConsumer<String, String>(properties);
        try {
            if (kafka.partitionsFor(topic).size() != 1) {
                throw new IllegalArgumentException("The in-memory exercise requires one commands partition");
            }
            var connected = new KafkaCommandConsumer(kafka, topic);
            // Establish the starting offset before exposing HTTP readiness or accepting commands.
            while (kafka.assignment().isEmpty()) kafka.poll(POLL_TIMEOUT);
            kafka.seekToEnd(kafka.assignment());
            for (var partition : kafka.assignment()) kafka.position(partition);
            return connected;
        } catch (RuntimeException exception) {
            kafka.close();
            throw exception;
        }
    }

    @Override
    public void poll(CommandHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        ConsumerRecords<String, String> records;
        try {
            records = consumer.poll(POLL_TIMEOUT);
        } catch (WakeupException exception) {
            if (stopping) return;
            throw exception;
        }
        try {
            for (var record : records) {
                handler.handle(new CommandMessage(topic, record.key(), record.value(), record.partition(), record.offset()));
            }
        } catch (RuntimeException exception) {
            // poll advances positions before handling. Rewind the whole batch for a safe retry.
            for (var partition : records.partitions()) {
                try {
                    consumer.seek(partition, records.records(partition).getFirst().offset());
                } catch (RuntimeException rewindFailure) {
                    exception.addSuppressed(rewindFailure);
                }
            }
            throw exception;
        }
        if (!records.isEmpty()) {
            try {
                consumer.commitSync();
            } catch (WakeupException exception) {
                if (!stopping) throw exception;
                // Finish committing the handled batch before leaving the group.
                consumer.commitSync();
            }
        }
    }

    public boolean isStopping() {
        return stopping;
    }

    /** Thread-safe Kafka wakeup; only the polling thread closes the consumer. */
    public void requestStop() {
        stopping = true;
        consumer.wakeup();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
