package ru.cherdakmd.chrdkqueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Очередь входа FIFO + бронирование освободившегося слота.
 *
 * <p>Жизненный цикл: обычный игрок при полном сервере добавляется в очередь
 * — и каждая следующая попытка входа снова получает kick с номером места.
 * Когда слот освобождается (кто-то вышел), первому из очереди открывается окно
 * {@code reservation-seconds} на подключение — в это время слот для остальных
 * закрыт. Не успел → запись сбрасывается, бронь уходит следующему.</p>
 *
 * <p>Всё состояние — только в памяти (перезапуск сервера обнуляет очередь,
 * это осознанное упрощение: «терпеливые» просто встанут снова).</p>
 */
public final class QueueService {

    public enum Decision { ALLOW, DENY_FULL, DENY_RESERVED, DENY_OVERFULL }

    public record Entry(String name, long lastSeenMillis) {
    }

    public record Verdict(Decision decision, int position, int online) {
        static Verdict allow(int online) {
            return new Verdict(Decision.ALLOW, 0, online);
        }
    }

    private final Deque<Entry> queue = new ArrayDeque<>();
    private String reservedFor;
    private long reservedUntilMillis;

    private volatile QueueConfig config;

    public QueueService(QueueConfig initial) {
        this.config = initial;
    }

    public void updateConfig(QueueConfig newConfig) {
        this.config = newConfig;
    }

    public QueueConfig config() {
        return config;
    }

    /**
     * Решение для попытки входа обычного (не VIP) игрока.
     * ВЫЗЫВАТЬ ТОЛЬКО с vip=false пути — VIP обрабатываются отдельно.
     */
    public synchronized Verdict decide(String playerName, int onlineNow) {
        QueueConfig cfg = config;
        if (!cfg.enabled()) {
            return Verdict.allow(onlineNow);
        }
        long now = System.currentTimeMillis();

        expireStale(now);
        dropExpiredReservation(now);

        // 1) собственная активная бронь — пропускаем, если слот ещё есть
        if (reservedFor != null && reservedFor.equalsIgnoreCase(playerName)) {
            if (onlineNow < cfg.regularLimit()) {
                consume(playerName);
                return Verdict.allow(onlineNow);
            }
            // слот занялся в торговой гонке — не блокируем игрока, его резерв жив
            remove(playerName);
            return Verdict.allow(onlineNow);
        }

        // 2) слот свободен и никем не зарезервирован — обычный вход
        if (onlineNow < cfg.regularLimit() && reservedFor == null) {
            remove(playerName);
            return Verdict.allow(onlineNow);
        }

        // 3) слот занят (или бронь чужая) — встаём в очередь
        if (indexOf(playerName) < 0) {
            if (queue.size() >= cfg.maxQueue()) {
                return new Verdict(Decision.DENY_OVERFULL, queue.size() + 1, onlineNow);
            }
            queue.addLast(new Entry(playerName, now));
        } else {
            touch(playerName, now);
        }
        int position = indexOf(playerName) + 1;
        if (reservedFor != null) {
            return new Verdict(Decision.DENY_RESERVED, position, onlineNow);
        }
        return new Verdict(Decision.DENY_FULL, position, onlineNow);
    }

    /**
     * Игрок вышел — возможно, образовался окно брони для первого в очереди.
     * Вызывается синхронным QuitEvent после того как Bukkit уже учёл уход.
     */
    public synchronized void maybeReserveNext(int onlineAfterQuit) {
        QueueConfig cfg = config;
        if (!cfg.enabled() || onlineAfterQuit >= cfg.regularLimit()) {
            return;
        }
        long now = System.currentTimeMillis();
        expireStale(now);
        dropExpiredReservation(now);
        if (reservedFor != null || queue.isEmpty()) {
            return;
        }
        Entry head = queue.peekFirst();
        reservedFor = head.name();
        reservedUntilMillis = now + cfg.reservationSeconds() * 1000L;
    }

    /** Очистить очередь (админ-действие). Возвращает сколько было записей. */
    /** Игрок зашёл: его запись и его бронь (если была) гаснут — просто страховка. */
    public synchronized void consumeJoin(String playerName) {
        remove(playerName);
        if (reservedFor != null && reservedFor.equalsIgnoreCase(playerName)) {
            reservedFor = null;
            reservedUntilMillis = 0L;
        }
    }

    public synchronized int clear() {
        int size = queue.size();
        queue.clear();
        reservedFor = null;
        reservedUntilMillis = 0L;
        return size;
    }

    /** Неблокирующий снимок очереди для /queue info. */
    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized String reservedFor() {
        return reservedFor;
    }

    public synchronized long reservedUntilMillis() {
        return reservedUntilMillis;
    }

    public synchronized boolean isReservedFor(String playerName) {
        return reservedFor != null && reservedFor.equalsIgnoreCase(playerName);
    }

    /* ---------------------------------------------------------------- *
     *  Внутренние помощники
     * ---------------------------------------------------------------- */

    private void consume(String playerName) {
        remove(playerName);
        if (reservedFor != null && reservedFor.equalsIgnoreCase(playerName)) {
            reservedFor = null;
            reservedUntilMillis = 0L;
        }
    }

    private void remove(String playerName) {
        queue.removeIf(entry -> entry.name().equalsIgnoreCase(playerName));
    }

    private int indexOf(String playerName) {
        int index = 0;
        for (Entry entry : queue) {
            if (entry.name().equalsIgnoreCase(playerName)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private void touch(String playerName, long now) {
        // пересоздать запись в ЗАДНЮЮ — нет, порядок сохраняем; просто обновляем метку
        List<Entry> copy = new ArrayList<>(queue);
        queue.clear();
        for (Entry entry : copy) {
            if (entry.name().equalsIgnoreCase(playerName)) {
                queue.addLast(new Entry(entry.name(), now));
            } else {
                queue.addLast(entry);
            }
        }
    }

    /** Бросить игроков, которые давно не пытались зайти. */
    private void expireStale(long now) {
        long lifetime = config.expireMinutes() * 60_000L;
        queue.removeIf(entry -> now - entry.lastSeenMillis() > lifetime);
    }

    /** Срок окна вышел и игрок не вошёл → бронь сгорает, его запись снимается. */
    private void dropExpiredReservation(long now) {
        if (reservedFor == null || now < reservedUntilMillis) {
            return;
        }
        String expired = reservedFor;
        reservedFor = null;
        reservedUntilMillis = 0L;
        remove(expired); // шанс проспал — в конец очереди НЕ возвращаем
    }
}
