package br.com.mb.ledger.jooq;

/** Infrastructure failure: propagates so Kafka offsets are not committed as a business rejection. */
public final class LedgerStorageException extends RuntimeException {

    public LedgerStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
