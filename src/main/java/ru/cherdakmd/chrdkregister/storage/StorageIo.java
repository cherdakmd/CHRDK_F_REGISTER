package ru.cherdakmd.chrdkregister.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Поток барьерной записи файлов состояния CHRDK_Core.
 *
 * <p>Финансовые файлы ({@code bank.json}, {@code paid-actions.json},
 * {@code bank-charge.json}) пишутся барьером «временный файл + force + атомарная
 * замена + force каталога». Раньше барьер выполнялся на вызывающем потоке: во
 * время задержки диска главный поток сервера стоял вместе с ним — Watchdog
 * печатал дампы 10+ секунд, а затянувшийся застой заканчивался аварийной
 * остановкой сервера (инцидент 18.11.2026). Теперь барьер выполняется здесь,
 * а вызывающий поток ждёт не дольше таймаута.</p>
 *
 * <p>Семантика успеха не изменилась: когда {@link #run} вернулся без ошибки —
 * данные на диске с force, как и раньше. Изменился только отказ: диск не
 * ответил за таймаут — {@link StallException} (подкласс {@link IOException}),
 * и модуль останавливается по своему fail-closed сценарию (карантин до
 * перезапуска), а не висит. Задача при этом может завершиться позже; исход
 * «запись могла состояться» соответствует существующей семантике сбоя после
 * атомарной замены (см. тесты afterRename-ветки).</p>
 *
 * <p>Очередь одна на все финансовые файлы: снимок autosave никогда не
 * перезапишет более свежую квитанцию — порядок записи совпадает с порядком
 * постановки. Задача не должна вызывать {@link #run} на этом же экземпляре:
 * поток один, будет самоблокировка.</p>
 */
public final class StorageIo {

    /** Таймаут барьера записи: вдвое ниже Watchdog-порога 10 секунд. */
    public static final long BARRIER_TIMEOUT_MS = 5_000L;

    private static final StorageIo SHARED = new StorageIo(BARRIER_TIMEOUT_MS);

    /** Общий поток записи ядра: финансовые файлы не конкурируют за диск и порядок. */
    public static StorageIo shared() {
        return SHARED;
    }

    /** Диск не ответил за отведённое время; исход записи неопределённый. */
    public static final class StallException extends IOException {
        public StallException(String message) {
            super(message);
        }
    }

    /** Шаг барьерной записи; исключения пробрасываются вызывающему потоку. */
    @FunctionalInterface
    public interface IoOperation {
        void run() throws IOException;
    }

    private final long timeoutMs;
    private final ExecutorService executor;

    public StorageIo(long timeoutMs) {
        if (timeoutMs < 1) throw new IllegalArgumentException("timeout");
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "chrdk-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Выполнить барьер записи и дождаться результата не дольше таймаута экземпляра. */
    public void run(IoOperation operation) throws IOException {
        run(operation, timeoutMs);
    }

    /**
     * Выполнить барьер записи и дождаться результата не дольше {@code timeoutMs}.
     *
     * <p>По таймауту задача, ещё не начавшаяся, отменяется и не пишется вовсе;
     * начавшаяся доводится до конца в фоне — вызывающий модуль обязан
     * остановиться по своему fail-closed сценарию (исход «запись могла
     * состояться» не разрешается автоматически).</p>
     */
    public void run(IoOperation operation, long timeoutMs) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Callable<Object> task = () -> {
            operation.run();
            return null;
        };
        Future<?> future = executor.submit(task);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException stall) {
            future.cancel(false);
            throw new StallException("Диск не ответил за " + timeoutMs + " мс; запись состояния в неопределённом состоянии");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание записи прервано", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException(cause);
        }
    }
}
