package ru.cherdakmd.chrdkvk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Коалесцирующий буфер исходящих сообщений глобального чата в беседу ВКонтакте.
 *
 * <p>Склеивает всплески сообщений (до {@code maxLines} строк за окно {@code windowMillis})
 * в один вызов {@code messages.send}, предотвращая ошибку VK API {@code 9 (Flood control)}
 * при активном общении на сервере.</p>
 */
public final class VkChatBatcher implements AutoCloseable {

    private final int windowMillis;
    private final int maxLines;
    private final int maxChars;
    private final BiConsumer<Long, String> sender;
    private final ScheduledExecutorService timer;

    private long pendingPeerId;
    private final List<String> buffer = new ArrayList<>();
    private int bufferedChars;
    private ScheduledFuture<?> scheduledFlush;
    private boolean closed;

    public VkChatBatcher(int windowMillis, int maxLines, int maxChars, BiConsumer<Long, String> sender) {
        this.windowMillis = Math.clamp(windowMillis, 0, 5_000);
        this.maxLines = Math.clamp(maxLines, 1, 10);
        this.maxChars = Math.clamp(maxChars, 200, 3_500);
        this.sender = sender;
        this.timer = this.windowMillis > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "CHRDK-VK-ChatBatcher");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    public synchronized void enqueue(long peerId, String line) {
        if (closed || peerId <= 0L || line == null || line.isBlank()) {
            return;
        }
        if (windowMillis == 0) {
            sender.accept(peerId, line);
            return;
        }
        if (pendingPeerId != 0L && pendingPeerId != peerId) {
            flushLocked();
        }
        if (!buffer.isEmpty() && (buffer.size() >= maxLines || bufferedChars + 1 + line.length() > maxChars)) {
            flushLocked();
        }
        pendingPeerId = peerId;
        buffer.add(line);
        bufferedChars += (buffer.size() > 1 ? 1 : 0) + line.length();

        if (buffer.size() >= maxLines) {
            flushLocked();
        } else if (scheduledFlush == null || scheduledFlush.isDone()) {
            scheduledFlush = timer.schedule(this::flush, windowMillis, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void flush() {
        flushLocked();
    }

    private void flushLocked() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
        if (buffer.isEmpty() || pendingPeerId <= 0L) {
            buffer.clear();
            bufferedChars = 0;
            return;
        }
        long targetPeer = pendingPeerId;
        String combined = String.join("\n", buffer);
        buffer.clear();
        bufferedChars = 0;
        pendingPeerId = 0L;
        sender.accept(targetPeer, combined);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            flushLocked();
        }
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
