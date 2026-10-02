package br.com.mb.engine.snapshot;

import java.util.List;

public record BookSnapshotPriceLevel(
    long price,
    List<BookSnapshotOrder> orders
) {
}
