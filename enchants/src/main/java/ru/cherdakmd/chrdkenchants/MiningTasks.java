package ru.cherdakmd.chrdkenchants;

import java.util.HashMap;
import java.util.Map;

/** Однотиковая очередь с отменой ticket; старое действие не удаляет новое с тем же ключом. */
final class MiningTasks {
    interface Handle { void cancel(); }
    interface Scheduler { Handle submit(Runnable action); }
    private static final class Ticket { final java.util.UUID owner; Handle handle; Ticket(java.util.UUID owner) { this.owner = owner; } }
    private final Map<Object, Ticket> pending = new HashMap<>();
    private final Scheduler scheduler;
    MiningTasks(Scheduler scheduler) { this.scheduler = scheduler; }
    boolean submit(java.util.UUID owner, Object key, Runnable action) {
        if (pending.size() >= 4096 || pending.containsKey(key)) return false;
        Ticket ticket = new Ticket(owner); pending.put(key, ticket);
        try { ticket.handle = scheduler.submit(() -> { if (pending.remove(key, ticket)) action.run(); }); }
        catch (RuntimeException | Error failure) { pending.remove(key, ticket); throw failure; }
        return true;
    }
    void cancel(java.util.UUID owner) {
        var old = pending.values().stream().filter(ticket -> ticket.owner.equals(owner)).toList();
        pending.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
        for (Ticket ticket : old) if (ticket.handle != null) ticket.handle.cancel();
    }
    void clear() {
        var old = java.util.List.copyOf(pending.values()); pending.clear();
        for (Ticket ticket : old) if (ticket.handle != null) ticket.handle.cancel();
    }
    int size() { return pending.size(); }
}
