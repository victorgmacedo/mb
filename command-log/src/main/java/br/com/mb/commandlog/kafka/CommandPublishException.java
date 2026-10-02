package br.com.mb.commandlog.kafka;

public final class CommandPublishException extends RuntimeException {

    public CommandPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
