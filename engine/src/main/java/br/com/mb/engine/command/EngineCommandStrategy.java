package br.com.mb.engine.command;

import java.util.List;

/** Executes one command flow and returns its FIX events; transaction and delivery belong to the caller. */
@FunctionalInterface
public interface EngineCommandStrategy<C extends EngineCommand> {
    List<String> execute(C command);
}
