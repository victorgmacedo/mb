package br.com.mb.shared.fix;

public final class InvalidFixMessageException extends RuntimeException {

    public InvalidFixMessageException(String message) {
        super(message);
    }

    public InvalidFixMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
