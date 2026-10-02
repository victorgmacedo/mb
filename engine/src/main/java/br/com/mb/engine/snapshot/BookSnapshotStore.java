package br.com.mb.engine.snapshot;

import java.util.Optional;

public interface BookSnapshotStore {
    Optional<SnapshotCheckpoint> loadLatestValid(String topic);

    void save(SnapshotCheckpoint checkpoint);
}
