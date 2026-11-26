package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import ru.cherdakmd.chrdkfishing.FishToken;

/** Display-only selection: originals remain in storage until journaled confirmation. */
final class FishSaleBasket {
    static final int CAPACITY = 36;
    private static final long MAX_TOTAL = 1_000_000;
    enum Change { ADDED, REMOVED, DUPLICATE, TOO_EXPENSIVE, FULL, CHANGED }
    private final LinkedHashMap<Integer, FishSalePlanner.Entry> selected = new LinkedHashMap<>();
    private long total;

    Change toggle(int slot, FishToken.Token token) { return toggle(new FishSalePlanner.Entry(slot, Objects.requireNonNull(token), null)); }
    Change toggle(FishSalePlanner.Entry entry) {
        Objects.requireNonNull(entry, "entry"); int slot = entry.slot();
        var old = selected.get(slot);
        if (old != null) {
            removeSlot(slot);
            return old.equals(entry) ? Change.REMOVED : Change.CHANGED;
        }
        if (entry.token() != null && selected.values().stream().anyMatch(other -> other.token() != null && other.token().id().equals(entry.token().id()))) return Change.DUPLICATE;
        if (selected.size() >= CAPACITY) return Change.FULL;
        if (entry.price() > MAX_TOTAL - total) return Change.TOO_EXPENSIVE;
        selected.put(slot, entry); total += entry.price(); return Change.ADDED;
    }
    void removeAt(int index) {
        var entries = entries();
        if (index >= 0 && index < entries.size()) removeSlot(entries.get(index).slot());
    }
    private void removeSlot(int slot) {
        var removed = selected.remove(slot);
        if (removed != null) total -= removed.price();
    }
    void clear() { selected.clear(); total = 0; }
    boolean isEmpty() { return selected.isEmpty(); }
    long total() { return total; }
    List<FishSalePlanner.Entry> entries() { return List.copyOf(new ArrayList<>(selected.values())); }
    List<Integer> slots() { return List.copyOf(selected.keySet()); }
    boolean matches(FishSalePlanner.Plan plan) {
        return plan != null && plan.price() == total && plan.entries().equals(entries());
    }
}
