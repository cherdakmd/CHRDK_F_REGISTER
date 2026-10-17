package ru.cherdakmd.chrdkregister;

/**
 * Кольцо замеров задержки API DonatePay (последние N опросов).
 * Чистая структура данных — юнит-тестируема.
 */
final class LatencySeries {

    private final long[] ring;
    private int size;
    private int cursor;
    private long max;

    LatencySeries(int capacity) {
        this.ring = new long[Math.max(1, capacity)];
    }

    synchronized void add(long millis) {
        long value = Math.min(Long.MAX_VALUE / 2, Math.max(0, millis));
        ring[cursor] = value;
        cursor = (cursor + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
        // max пересчитываем всегда: вместимость маленькая (по умолчанию 20),
        // так дешевле, чем инкрементальная логика с протухающим максимумом
        max = 0;
        for (int i = 0; i < size; i++) {
            if (ring[i] > max) {
                max = ring[i];
            }
        }
    }

    /** Средняя задержка по накопленным замерам (0, если замеров ещё нет). */
    synchronized long avg() {
        if (size == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += ring[i];
        }
        return sum / size;
    }

    /** Худшая задержка среди накопленных замеров. */
    synchronized long max() {
        return max;
    }

    synchronized int samples() {
        return size;
    }
}
