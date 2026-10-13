package ru.cherdakmd.chrdktierforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сессионная статистика накатов по тирам (для {@code /tiers stats}):
 * сколько раз каждый тир выпал конкретному игроку (крафт/дроп/опознание).
 * Живёт в памяти, с перезапуском обнуляется — откровенная развлекательная метрика.
 */
public final class TierStatsKeeper {

    private final ConcurrentHashMap<UUID, Map<String, Integer>> byPlayer = new ConcurrentHashMap<>();

    public void count(UUID player, String tierId) {
        if (player == null || tierId == null) {
            return;
        }
        Map<String, Integer> row = byPlayer.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        row.merge(tierId, 1, Integer::sum);
    }

    public int totalOf(UUID player, String tierId) {
        Map<String, Integer> row = byPlayer.get(player);
        return row == null ? 0 : row.getOrDefault(tierId, 0);
    }

    public Map<String, Integer> row(UUID player) {
        Map<String, Integer> row = byPlayer.get(player);
        return row == null ? Map.of() : new HashMap<>(row);
    }

    /** Общее количество выпадений игрока (по всем тирам). */
    public int total(UUID player) {
        return row(player).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Серверное суммарное количество выпадений за сессию (все игроки, все тиры). */
    public int grandTotal() {
        return byPlayer.values().stream()
                .flatMap(row -> row.values().stream())
                .mapToInt(Integer::intValue)
                .sum();
    }

    public void reset(UUID player) {
        byPlayer.remove(player);
    }
}
