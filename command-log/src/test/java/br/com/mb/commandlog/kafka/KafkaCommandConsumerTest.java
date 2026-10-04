package br.com.mb.commandlog.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;

class KafkaCommandConsumerTest {

    @Test
    void finishesHandledBatchBeforeShutdownAndClosesOnCallerThread() {
        var partition = new TopicPartition("commands", 0);
        var mock = new MockConsumer<String, String>("earliest");
        try (var consumer = new KafkaCommandConsumer(mock, "commands")) {
            mock.rebalance(List.of(partition));
            mock.updateBeginningOffsets(Map.of(partition, 0L));
            mock.seek(partition, 0L);
            mock.schedulePollTask(() -> mock.addRecord(new ConsumerRecord<>("commands", 0, 0, "BTC/BRL", "first")));
            consumer.poll(message -> {
                assertEquals(0, message.partition());
                assertEquals(0L, message.offset());
                consumer.requestStop();
            });
            assertTrue(consumer.isStopping());
            assertEquals(1, mock.committed(Set.of(partition)).get(partition).offset());
            consumer.poll(message -> { throw new AssertionError("no new work after wakeup"); });
        }
        assertTrue(mock.closed());
    }

    @Test
    void propagatesUnexpectedWakeupAndDoesNotCommitFailedHandlers() {
        var partition = new TopicPartition("commands", 0);
        var mock = new MockConsumer<String, String>("earliest");
        try (var consumer = new KafkaCommandConsumer(mock, "commands")) {
            mock.wakeup();
            assertThrows(WakeupException.class, () -> consumer.poll(message -> {}));
            mock.rebalance(List.of(partition));
            mock.updateBeginningOffsets(Map.of(partition, 0L));
            mock.seek(partition, 0L);
            mock.schedulePollTask(() -> mock.addRecord(new ConsumerRecord<>("commands", 0, 0, "BTC/BRL", "first")));
            assertThrows(IllegalStateException.class, () -> consumer.poll(message -> { throw new IllegalStateException("failed"); }));
            assertTrue(mock.committed(Set.of(partition)).get(partition) == null);
            assertEquals(0L, mock.position(partition));
            mock.schedulePollTask(() -> mock.addRecord(new ConsumerRecord<>("commands", 0, 0, "BTC/BRL", "first")));
            consumer.poll(message -> assertEquals("first", message.value()));
            assertEquals(1L, mock.committed(Set.of(partition)).get(partition).offset());
        }
    }
}
