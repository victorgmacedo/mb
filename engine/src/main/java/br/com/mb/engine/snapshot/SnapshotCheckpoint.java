package br.com.mb.engine.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SnapshotCheckpoint(UUID id, String topic, Instant createdAt,
                                 Map<Integer, Long> nextOffsets, List<BookSnapshot> books) {
    public SnapshotCheckpoint {
        nextOffsets = Map.copyOf(nextOffsets);
        books = List.copyOf(books);
        if (topic == null || topic.isBlank() || id == null || createdAt == null
            || nextOffsets.entrySet().stream().anyMatch(entry -> entry.getKey() < 0 || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Invalid snapshot checkpoint metadata");
        }
    }
}
