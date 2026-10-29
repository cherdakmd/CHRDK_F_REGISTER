package ru.cherdakmd.chrdkenchants;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Один проверяемый preview на игрока; identity окна, а не только его тип/номер слота. */
final class AnvilQuotes<V, I> {
    static final int LIMIT = 4096;
    private final Map<UUID, Quote<V, I>> entries = new HashMap<>();

    record Quote<V, I>(V view, I left, I right, String rename, I result, int cost,
                       int maximum, long generation, boolean cumulative, boolean creative, long surcharge) {
        boolean couldBeRepeat(int cost, int materials) {
            // При нулевой доплате native-пересчёт имеет ту же цену, но сбрасывает расход книги в 0.
            return this.cost == cost && (surcharge > 0 || materials != 0);
        }
        boolean source(V view, I left, I right, String rename) {
            return this.view == view && Objects.equals(this.left, left)
                    && Objects.equals(this.right, right) && Objects.equals(this.rename, rename);
        }
        boolean output(I result, int cost, int materials, int maximum, long generation,
                       boolean cumulative, boolean creative) {
            return Objects.equals(this.result, result) && this.cost == cost && materials == 1
                    && this.maximum == maximum && this.generation == generation
                    && this.cumulative == cumulative && this.creative == creative;
        }
    }

    Quote<V, I> get(UUID player) { return entries.get(player); }
    boolean put(UUID player, Quote<V, I> quote) {
        if (!entries.containsKey(player) && entries.size() >= LIMIT) return false;
        entries.put(player, quote);
        return true;
    }
    void remove(UUID player) { entries.remove(player); }
    void clear() { entries.clear(); }
    int size() { return entries.size(); }
}
