package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;

import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Исходящий {@code GET /updates} MAX Bot API: fallback, когда webhook ещё не
 * настроен, и режим локальной разработки без HTTPS. Marker хранится между
 * запросами, поэтому обновления не теряются при переподключении.
 */
public final class MaxLongPollWorker implements AutoCloseable {

    private final MaxApiClient client;
    private final int waitSeconds;
    private final int reconnectSeconds;
    private final int limit;
    private final BiConsumer<JsonObject, Long> dispatcher;
    private final Logger logger;

    private volatile boolean running = true;
    private volatile boolean connected;
    private volatile String marker = "";
    private final Thread thread;

    public MaxLongPollWorker(MaxApiClient client, int waitSeconds, int reconnectSeconds,
                             int limit, BiConsumer<JsonObject, Long> dispatcher, Logger logger) {
        this.client = client;
        this.waitSeconds = Math.clamp(waitSeconds, 1, 60);
        this.reconnectSeconds = Math.clamp(reconnectSeconds, 2, 60);
        this.limit = Math.clamp(limit, 1, 1000);
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.thread = new Thread(this::runLoop, "CHRDK-MAX-LongPoll");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public boolean isConnected() {
        return running && connected && thread.isAlive();
    }

    public String marker() {
        return marker;
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                MaxApiClient.Updates batch = client.updates(marker, waitSeconds, limit);
                connected = true;
                marker = batch.marker();
                long now = System.currentTimeMillis();
                for (JsonObject update : batch.updates()) {
                    try {
                        dispatcher.accept(update, now);
                    } catch (RuntimeException exception) {
                        logger.log(Level.WARNING, "[CHRDK_SOCIAL] Ошибка обработки обновления MAX", exception);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                connected = false;
                if (running) {
                    logger.fine("[CHRDK_SOCIAL] MAX Long Poll переподключение: "
                            + failure.getClass().getSimpleName());
                    sleepReconnect();
                }
            }
        }
        connected = false;
    }

    private void sleepReconnect() {
        try {
            Thread.sleep(reconnectSeconds * 1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        connected = false;
        thread.interrupt();
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
