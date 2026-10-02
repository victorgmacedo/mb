package br.com.mb.commandlog.kafka;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaTopicReplayerTest {

    @Test
    void resumesAtNextOffsetAndExcludesRecordsBeyondCapturedBoundary() {
        var partition = new TopicPartition("journal", 0);
        try (var consumer = consumer(partition, 0L, 3L); var replayer = new KafkaTopicReplayer(consumer)) {
            consumer.schedulePollTask(() -> {
                consumer.addRecord(new ConsumerRecord<>("journal", 0, 2, "BTC/BRL", "included"));
                consumer.addRecord(new ConsumerRecord<>("journal", 0, 3, "BTC/BRL", "later"));
            });
            var result = replayer.replay("journal", Map.of(0, 2L));
            assertEquals(List.of("included"), result.messages().stream().map(message -> message.value()).toList());
            assertEquals(Map.of(0, 3L), result.nextOffsets());
        }
    }

    @Test
    void rejectsUnavailableHistoryInsteadOfSilentlySkippingIt() {
        var partition = new TopicPartition("journal", 0);
        try (var consumer = consumer(partition, 5L, 9L); var replayer = new KafkaTopicReplayer(consumer)) {
            assertThrows(IllegalStateException.class, () -> replayer.replay("journal"));
            assertThrows(IllegalStateException.class, () -> replayer.replay("journal", Map.of(0, 4L)));
            assertThrows(IllegalStateException.class, () -> replayer.replay("journal", Map.of(0, 10L)));
            assertThrows(IllegalStateException.class, () -> replayer.replay("journal", Map.of(1, 5L)));
            assertTrue(replayer.replay("journal", Map.of(0, 9L)).messages().isEmpty());
        }
    }

    private MockConsumer<String, String> consumer(TopicPartition partition, long beginning, long end) {
        var consumer = new MockConsumer<String, String>("none");
        consumer.updatePartitions("journal", List.of(new PartitionInfo("journal", 0, null, null, null)));
        consumer.updateBeginningOffsets(Map.of(partition, beginning));
        consumer.updateEndOffsets(Map.of(partition, end));
        return consumer;
    }
}
