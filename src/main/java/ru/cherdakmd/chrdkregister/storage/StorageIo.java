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
 * ответил за таймаут — {@link StallException} (подкласс {@link IOException}).
 * Если операция уже началась, модуль обязан перейти в fail-closed карантин;
 * если она гарантированно не началась, вызывающий может безопасно отклонить
 * запрос без карантина. Начатая задача может завершиться позже; исход
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
        private final boolean started;
        public StallException(String message) { this(message, true); }
        public StallException(String message, boolean started) {
            super(message);
            this.started = started;
        }
        /** false только если этот вызов гарантированно не начал свою операцию записи. */
        public boolean started() { return started; }
    }

    /** Линейная граница между началом операции и отменой ещё не начатой задачи. */
    private static final class StartGate {
        private boolean started;
        private boolean cancelled;
        synchronized boolean begin() {
            if (cancelled) return false;
            started = true;
            return true;
        }
        synchronized boolean cancelBeforeStart() {
            if (started) return false;
            cancelled = true;
            return true;
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
        StartGate gate = new StartGate();
        Callable<Object> task = () -> {
            if (gate.begin()) operation.run();
            return null;
        };
        Future<?> future = executor.submit(task);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException stall) {
            boolean cancelledBeforeStart = gate.cancelBeforeStart();
            future.cancel(false);
            throw new StallException(cancelledBeforeStart
                    ? "Очередь записи занята более " + timeoutMs + " мс; операция не начиналась"
                    : "Диск не ответил за " + timeoutMs + " мс; запись состояния в неопределённом состоянии",
                    !cancelledBeforeStart);
        } catch (InterruptedException interrupted) {
            gate.cancelBeforeStart();
            future.cancel(false);
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
