package br.com.mb.commandlog;

public interface CommandPublisher extends AutoCloseable {

    void publish(CommandMessage message);

    @Override
    void close();
}
