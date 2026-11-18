package ru.cherdakmd.chrdkregister.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поток барьерной записи: операция выполняется не на вызывающем потоке, таймаут
 * превращает зависание диска в fail-closed отказ, невыполненная задача отменяется.
 */
class StorageIoTest {

    private static void await(CountDownLatch gate) throws IOException {
        try {
            gate.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", interrupted);
        }
    }

    @Test
    void runsOperationAndReturnsOnlyAfterCompletion() throws Exception {
        var io = new StorageIo(5_000L);
        Thread caller = Thread.currentThread();
        AtomicBoolean onIoThread = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        io.run(() -> {
            onIoThread.set(Thread.currentThread().getName().equals("chrdk-io") && !Thread.currentThread().equals(caller));
            done.set(true);
        });
        assertTrue(done.get(), "задача завершается до возврата");
        assertTrue(onIoThread.get(), "задача выполняется потоком chrdk-io, а не вызывающим");
    }

    @Test
    void stallThrowsInsteadOfHangingAndLateCompletionIsTolerated() throws Exception {
        var io = new StorageIo(100L);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        long startedAt = System.nanoTime();
        assertThrows(StorageIo.StallException.class, () -> io.run(() -> {
            await(gate);
            finished.set(true);
        }));
        assertTrue((System.nanoTime() - startedAt) / 1_000_000L < 5_000L, "вызывающий поток не ждёт диск бесконечно");
        gate.countDown();
        io.run(() -> { }, 5_000L);
        assertTrue(finished.get(), "начатая задача доводится до конца в фоне");
    }

    @Test
    void queuedOperationCancelledOnStallNeverRuns() throws Exception {
        var io = new StorageIo(100L);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelledRan = new AtomicBoolean();
        Thread blocker = new Thread(() -> {
            try {
                io.run(() -> {
                    started.countDown();
                    await(gate);
                });
            } catch (IOException ignored) {
                // таймаут ожидания не прерывает занятую задачу
            }
        }, "test-blocker");
        blocker.setDaemon(true);
        blocker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(StorageIo.StallException.class, () -> io.run(() -> cancelledRan.set(true)));
        gate.countDown();
        io.run(() -> { }, 5_000L);
        assertFalse(cancelledRan.get(), "задача, не начавшаяся к моменту таймаута, отменяется и не пишет файл");
    }

    @Test
    void propagatesIoAndRuntimeFailures() {
        var io = new StorageIo(5_000L);
        IOException failure = assertThrows(IOException.class, () -> io.run(() -> {
            throw new IOException("бита");
        }));
        assertEquals("бита", failure.getMessage());
        assertFalse(failure instanceof StorageIo.StallException);
        assertThrows(IllegalStateException.class, () -> io.run(() -> {
            throw new IllegalStateException("стоп");
        }));
    }

    @Test
    void sharedInstanceIsSingleton() {
        assertSame(StorageIo.shared(), StorageIo.shared());
    }
}
