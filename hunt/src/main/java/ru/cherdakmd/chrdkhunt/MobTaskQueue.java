package ru.cherdakmd.chrdkhunt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Очередь одного тика с дедупликацией; отменённый ticket не удалит новый с тем же ключом. */
final class MobTaskQueue {
    static final int LIMIT = 4096;
    interface Handle { void cancel(); }
    interface Scheduler { Handle submit(Runnable action); }
    private record Key(UUID id, String kind) { }
    private static final class Ticket { Handle handle; }
    private final Map<Key, Ticket> pending = new HashMap<>();
    private final Scheduler scheduler;
    private boolean stopped;
    MobTaskQueue(Scheduler scheduler) { this.scheduler = scheduler; }
    boolean submit(UUID id, String kind, Runnable action) {
        Key key = new Key(id, kind);
        if (stopped || pending.size() >= LIMIT || pending.containsKey(key)) return false;
        Ticket ticket = new Ticket();
        pending.put(key, ticket);
        try {
            ticket.handle = scheduler.submit(() -> {
                if (pending.remove(key, ticket) && !stopped) action.run();
            });
        } catch (RuntimeException | Error failure) {
            pending.remove(key, ticket);
            throw failure;
        }
        return true;
    }
    void cancel(UUID id, String kind) {
        Ticket ticket = pending.remove(new Key(id, kind));
        if (ticket != null && ticket.handle != null) ticket.handle.cancel();
    }
    boolean contains(UUID id, String kind) { return pending.containsKey(new Key(id, kind)); }
    int size() { return pending.size(); }
    void clear() {
        var old = java.util.List.copyOf(pending.values());
        pending.clear();
        for (Ticket ticket : old) if (ticket.handle != null) ticket.handle.cancel();
    }
    void stop() { stopped = true; clear(); }
}
