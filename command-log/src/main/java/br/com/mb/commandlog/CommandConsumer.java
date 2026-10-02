package br.com.mb.commandlog;

public interface CommandConsumer extends AutoCloseable {

    void poll(CommandHandler handler);

    @Override
    void close();
}
