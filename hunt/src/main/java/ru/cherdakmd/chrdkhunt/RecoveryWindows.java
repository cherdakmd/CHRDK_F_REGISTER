package ru.cherdakmd.chrdkhunt;

import java.util.*;

/** Один таймер на моба, ограниченное число окон; старый callback не заберёт новый ticket. */
final class RecoveryWindows {
    static final int LIMIT = 4096;
    static final class Ticket {
        final UUID owner;
        Ticket(UUID owner) { this.owner = owner; }
    }
    private final Map<UUID, Ticket> entries = new HashMap<>();
    Ticket replace(UUID mob, UUID owner) {
        if (mob == null || owner == null || !entries.containsKey(mob) && entries.size() >= LIMIT) return null;
        Ticket ticket = new Ticket(owner);
        entries.put(mob, ticket);
        return ticket;
    }
    boolean owns(UUID mob, Ticket ticket) { return ticket != null && entries.get(mob) == ticket; }
    boolean remove(UUID mob, Ticket ticket) { return entries.remove(mob, ticket); }
    void remove(UUID mob) { entries.remove(mob); }
    List<UUID> forget(UUID owner) {
        List<UUID> result = new ArrayList<>();
        entries.entrySet().removeIf(entry -> {
            if (!entry.getValue().owner.equals(owner)) return false;
            result.add(entry.getKey()); return true;
        });
        return result;
    }
    int size() { return entries.size(); }
    void clear() { entries.clear(); }
}
