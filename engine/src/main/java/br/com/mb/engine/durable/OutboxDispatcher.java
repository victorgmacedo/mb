package br.com.mb.engine.durable;

import br.com.mb.commandlog.CommandPublisher;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Publication is retried independently of command intake and can run on any engine pod. */
public final class OutboxDispatcher implements AutoCloseable {
    private final java.util.concurrent.ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final DurableEngineStore store;
    private final CommandPublisher publisher;
    private final Consumer<RuntimeException> onFailure;

    public OutboxDispatcher(DurableEngineStore store, CommandPublisher publisher, Consumer<RuntimeException> onFailure) {
        this.store = store;
        this.publisher = publisher;
        this.onFailure = onFailure;
    }

    public void start() {
        worker.scheduleWithFixedDelay(() -> {
            try {
                store.publishPending(publisher, 100);
            } catch (RuntimeException exception) {
                onFailure.accept(exception);
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                worker.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
