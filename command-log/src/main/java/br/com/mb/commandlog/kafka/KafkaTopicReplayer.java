package br.com.mb.commandlog.kafka;

import br.com.mb.commandlog.CommandMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaTopicReplayer implements AutoCloseable {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);

    private final Consumer<String, String> consumer;

    public KafkaTopicReplayer(Consumer<String, String> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
    }

    public static KafkaTopicReplayer connect(String bootstrapServers, String clientId) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new KafkaTopicReplayer(new KafkaConsumer<>(properties));
    }

    public List<CommandMessage> replay(String topic) {
        return replay(topic, Map.of()).messages();
    }

    public ReplayResult replay(String topic, Map<Integer, Long> nextOffsets) {
        Objects.requireNonNull(topic, "topic must not be null");
        var partitions = partitions(topic);
        if (partitions.isEmpty()) {
            if (!nextOffsets.isEmpty()) {
                throw new IllegalStateException("Snapshot references missing journal partitions");
            }
            return new ReplayResult(List.of(), Map.of());
        }

        consumer.assign(partitions);
        var beginningOffsets = consumer.beginningOffsets(partitions);
        var endOffsets = consumer.endOffsets(partitions);
        if (!partitions.stream().map(TopicPartition::partition).toList().containsAll(nextOffsets.keySet())) {
            throw new IllegalStateException("Snapshot references missing journal partitions");
        }
        for (var partition : partitions) {
            var start = nextOffsets.getOrDefault(partition.partition(), 0L);
            if (start < beginningOffsets.get(partition) || start > endOffsets.get(partition)) {
                throw new IllegalStateException("Journal range unavailable for partition " + partition.partition());
            }
            consumer.seek(partition, start);
        }
        var messages = new ArrayList<CommandMessage>();

        while (!finished(partitions, endOffsets)) {
            var records = consumer.poll(POLL_TIMEOUT);
            for (var record : records) {
                if (record.offset() < endOffsets.get(new TopicPartition(topic, record.partition()))) {
                    messages.add(new CommandMessage(topic, record.key(), record.value()));
                }
            }
        }

        var offsets = new HashMap<Integer, Long>();
        endOffsets.forEach((partition, offset) -> offsets.put(partition.partition(), offset));
        return new ReplayResult(messages, offsets);
    }

    public record ReplayResult(List<CommandMessage> messages, Map<Integer, Long> nextOffsets) {
        public ReplayResult {
            messages = List.copyOf(messages);
            nextOffsets = Map.copyOf(nextOffsets);
        }
    }

    @Override
    public void close() {
        consumer.close();
    }

    private List<TopicPartition> partitions(String topic) {
        return consumer.partitionsFor(topic).stream()
            .map(partition -> new TopicPartition(topic, partition.partition()))
            .toList();
    }

    private boolean finished(List<TopicPartition> partitions, Map<TopicPartition, Long> endOffsets) {
        for (var partition : partitions) {
            if (consumer.position(partition) < endOffsets.get(partition)) {
                return false;
            }
        }
        return true;
    }
}
