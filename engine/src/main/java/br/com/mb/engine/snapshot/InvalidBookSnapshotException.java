package br.com.mb.engine.snapshot;

public final class InvalidBookSnapshotException extends RuntimeException {

    public InvalidBookSnapshotException(String message) {
        super(message);
    }

    public InvalidBookSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
