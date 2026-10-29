package ru.cherdakmd.chrdkregister;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Онлайн-часы на монотонном времени. Ни офлайн timestamp, ни часы остановленного JVM не сохраняются. */
final class RewardBoostClock {
    private record Cursor(String name, long nanos) { }
    private final Map<UUID, Cursor> online = new HashMap<>();
    private final BiConsumer<String, Long> debit;

    RewardBoostClock(BiConsumer<String, Long> debit) { this.debit = debit; }

    synchronized void resume(UUID id, String name, long nowNanos) {
        online.putIfAbsent(id, new Cursor(name, nowNanos));
    }

    synchronized void sample(UUID id, String name, long nowNanos) {
        Cursor cursor = online.get(id);
        if (cursor == null) {
            resume(id, name, nowNanos);
            return;
        }
        long millis = elapsed(cursor, nowNanos);
        debit.accept(cursor.name(), millis);
        online.put(id, new Cursor(name, cursor.nanos() + millis * 1_000_000L));
    }

    synchronized void settle(String name, long nowNanos) {
        UUID id = online.entrySet().stream().filter(entry -> entry.getValue().name().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (id != null) sample(id, name, nowNanos);
    }

    synchronized void pause(UUID id, long nowNanos) {
        Cursor cursor = online.remove(id);
        if (cursor != null) debit.accept(cursor.name(), elapsed(cursor, nowNanos));
    }

    synchronized void stop(long nowNanos) {
        for (UUID id : java.util.List.copyOf(online.keySet())) pause(id, nowNanos);
    }

    private static long elapsed(Cursor cursor, long nowNanos) {
        return Math.max(0, (nowNanos - cursor.nanos()) / 1_000_000L);
    }
}
