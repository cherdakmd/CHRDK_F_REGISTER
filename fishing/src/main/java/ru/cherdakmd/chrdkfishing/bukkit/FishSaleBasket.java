package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import ru.cherdakmd.chrdkfishing.FishToken;

/** A display-only selection. Actual fish remain in storage until the journaled sale. */
final class FishSaleBasket {
    static final int CAPACITY = 36;
    private static final long MAX_TOTAL = 1_000_000;
    record Entry(int storageSlot, FishToken.Token token) { }
    enum Change { ADDED, REMOVED, DUPLICATE, TOO_EXPENSIVE, FULL, CHANGED }
    private final LinkedHashMap<Integer, FishToken.Token> selected = new LinkedHashMap<>();
    private long total;

    Change toggle(int slot, FishToken.Token token) {
        if (slot < 0 || slot >= CAPACITY) throw new IllegalArgumentException("storage slot");
        Objects.requireNonNull(token, "fish");
        FishToken.Token old = selected.get(slot);
        if (old != null) {
            removeSlot(slot);
            return old.equals(token) ? Change.REMOVED : Change.CHANGED;
        }
        if (selected.values().stream().anyMatch(fish -> fish.id().equals(token.id()))) return Change.DUPLICATE;
        if (selected.size() >= CAPACITY) return Change.FULL;
        if (token.quotedPrice() > MAX_TOTAL - total) return Change.TOO_EXPENSIVE;
        selected.put(slot, token);
        total += token.quotedPrice();
        return Change.ADDED;
    }
    void removeAt(int index) {
        var entries = entries();
        if (index >= 0 && index < entries.size()) removeSlot(entries.get(index).storageSlot());
    }
    private void removeSlot(int slot) {
        var removed = selected.remove(slot);
        if (removed != null) total -= removed.quotedPrice();
    }
    void clear() { selected.clear(); total = 0; }
    boolean isEmpty() { return selected.isEmpty(); }
    long total() { return total; }
    List<Entry> entries() {
        var entries = new ArrayList<Entry>(selected.size());
        selected.forEach((slot, token) -> entries.add(new Entry(slot, token)));
        return List.copyOf(entries);
    }
    List<Integer> slots() { return List.copyOf(selected.keySet()); }
    /** Recheck the identities and exact displayed prices in the planner's native snapshot. */
    boolean matches(FishSalePlanner.Plan plan) {
        return plan != null && plan.price() == total && plan.fish().equals(List.copyOf(selected.values()));
    }
}
