package br.com.mb.engine.snapshot;

import java.time.Instant;

public record BookSnapshotOrder(
    String accountId,
    String clientOrderId,
    long remainingQuantity,
    long entrySequence,
    Instant enteredAt
) {
}
