package br.com.mb.commandlog.kafka;

import br.com.mb.commandlog.CommandConsumer;
import br.com.mb.commandlog.CommandHandler;
import br.com.mb.commandlog.CommandMessage;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaCommandConsumer implements CommandConsumer {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final PartitionListener NO_PARTITION_LISTENER = new PartitionListener() {
        public void assigned(List<Integer> partitions) {}
        public void revoked(List<Integer> partitions) {}
    };

    private final Consumer<String, String> consumer;
    private final String topic;
    private volatile boolean stopping;

    public interface PartitionListener {
        void assigned(List<Integer> partitions);
        void revoked(List<Integer> partitions);
    }

    public KafkaCommandConsumer(Consumer<String, String> consumer, String topic) {
        this(consumer, topic, NO_PARTITION_LISTENER);
    }

    public KafkaCommandConsumer(Consumer<String, String> consumer, String topic, PartitionListener listener) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        this.consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                listener.assigned(partitions.stream().map(TopicPartition::partition).toList());
            }
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                listener.revoked(partitions.stream().map(TopicPartition::partition).toList());
            }
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                listener.revoked(partitions.stream().map(TopicPartition::partition).toList());
            }
        });
    }

    public static KafkaCommandConsumer connect(String bootstrapServers, String groupId, String topic) {
        return connect(bootstrapServers, groupId, topic, NO_PARTITION_LISTENER);
    }

    public static KafkaCommandConsumer connect(String bootstrapServers, String groupId, String topic,
                                               PartitionListener listener) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new KafkaCommandConsumer(new KafkaConsumer<>(properties), topic, listener);
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
