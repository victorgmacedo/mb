package br.com.mb.commandlog;

@FunctionalInterface
public interface CommandHandler {

    void handle(CommandMessage message);
}
