package br.com.mb.engine.snapshot;

import java.util.List;

public record BookSnapshot(
    String instrument,
    List<BookSnapshotPriceLevel> bids,
    List<BookSnapshotPriceLevel> asks,
    long lastEntrySequence,
    int schemaVersion
) {
}
