package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;

import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Фоновый поток исходящего Bots Long Poll API ВКонтакте.
 *
 * <p>Поддерживает протокол восстановления {@code failed = 1} (обновление {@code ts}),
 * {@code failed = 2/3} (перезапрос {@code groups.getLongPollServer}) и корректно
 * останавливается при выключении или перезагрузке конфигурации.</p>
 */
public final class VkLongPollWorker implements AutoCloseable {

    private final VkApiClient client;
    private final long groupId;
    private final int waitSeconds;
    private final int reconnectSeconds;
    private final BiConsumer<JsonObject, Long> dispatcher;
    private final Logger logger;

    private volatile boolean running = true;
    private volatile boolean connected;
    private final Thread thread;

    public VkLongPollWorker(VkApiClient client,
                            long groupId,
                            int waitSeconds,
                            int reconnectSeconds,
                            BiConsumer<JsonObject, Long> dispatcher,
                            Logger logger) {
        this.client = client;
        this.groupId = groupId;
        this.waitSeconds = Math.clamp(waitSeconds, 5, 25);
        this.reconnectSeconds = Math.clamp(reconnectSeconds, 2, 60);
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.thread = new Thread(this::runLoop, "CHRDK-VK-LongPoll");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public boolean isConnected() {
        return running && connected && thread.isAlive();
    }

    private void runLoop() {
        VkApiClient.LongPollEndpoint endpoint = null;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (endpoint == null) {
                    endpoint = client.getLongPollServer(groupId);
                    connected = true;
                }
                VkApiClient.LongPollBatch batch = client.checkLongPoll(endpoint, waitSeconds);
                if (!running) {
                    break;
                }
                int failed = batch.failed();
                if (failed == 0) {
                    endpoint = new VkApiClient.LongPollEndpoint(
                            endpoint.server(), endpoint.key(), batch.ts());
                    long now = System.currentTimeMillis();
                    for (JsonObject update : batch.updates()) {
                        try {
                            dispatcher.accept(update, now);
                        } catch (RuntimeException exception) {
                            logger.log(Level.WARNING,
                                    "[CHRDK_VK] Ошибка при обработке входящего пакета Long Poll", exception);
                        }
                    }
                } else if (failed == 1) {
                    endpoint = new VkApiClient.LongPollEndpoint(
                            endpoint.server(), endpoint.key(), batch.ts());
                } else if (failed == 2 || failed == 3) {
                    endpoint = null;
                } else {
                    connected = false;
                    logger.warning("[CHRDK_VK] Bots Long Poll вернул код failed=" + failed
                            + " — проверьте версию VK API в настройках.");
                    sleepReconnect();
                    endpoint = null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                connected = false;
                endpoint = null;
                if (running) {
                    logger.fine("[CHRDK_VK] Переподключение Bots Long Poll: " + exception.getMessage());
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
