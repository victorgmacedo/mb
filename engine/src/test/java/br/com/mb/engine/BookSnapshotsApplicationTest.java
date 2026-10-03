package br.com.mb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BookSnapshotsApplicationTest {

    @Test
    @Timeout(5)
    void propagatesSnapshotFailureToCaller() {
        var failure = new IllegalStateException("snapshot failed");
        var executions = new AtomicInteger();

        var exception = assertThrows(ExecutionException.class,
            () -> BookSnapshotsApplication.runPeriodically(() -> {
                if (executions.incrementAndGet() == 2) {
                    throw failure;
                }
            }, 1));

        assertSame(failure, exception.getCause());
        assertEquals(2, executions.get());
    }

    @Test
    @Timeout(5)
    void interruptionStopsWorkerBeforeReturning() throws InterruptedException {
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        var caller = new Thread(() -> {
            try {
                BookSnapshotsApplication.runPeriodically(() -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        stopped.countDown();
                    }
                }, 60_000);
            } catch (InterruptedException | ExecutionException exception) {
                outcome.set(exception);
            }
        });
        caller.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(0, stopped.getCount());
            assertTrue(outcome.get() instanceof InterruptedException);
        } finally {
            caller.interrupt();
            caller.join(2_000);
        }
    }
}
