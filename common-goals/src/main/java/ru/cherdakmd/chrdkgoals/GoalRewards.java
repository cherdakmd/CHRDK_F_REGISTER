package ru.cherdakmd.chrdkgoals;

import java.util.LinkedHashMap;
import java.util.Map;

/** Делёж пула наград между участниками: пропорционально вкладу, минимум 1 каждому. */
public final class GoalRewards {

    private GoalRewards() {
    }

    /**
     * Доля каждого участника = floor(pool * вклад / суммарный вклад); тем, кто вообще
     * поучаствовал, гарантируется минимум 1. Остаток пула недоразданным не «сгорает» —
     * дотягиваем лидеру.
     */
    public static Map<String, Integer> distribute(int pool, Map<String, Integer> contributions,
                                                  int bonusPercent) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (pool <= 0 || contributions == null || contributions.isEmpty()) {
            return result;
        }
        int total = contributions.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return result;
        }
        int poolWithBonus = (int) Math.round(pool * (1 + Math.max(0, bonusPercent) / 100.0));
        int assigned = 0;
        String leader = null;
        int leaderValue = -1;
        for (Map.Entry<String, Integer> entry : contributions.entrySet()) {
            int value = entry.getValue();
            int share = Math.max(1, (int) Math.floor(poolWithBonus * (double) value / total));
            result.put(entry.getKey(), share);
            assigned += share;
            if (value > leaderValue) {
                leaderValue = value;
                leader = entry.getKey();
            }
        }
        if (leader != null && assigned < poolWithBonus) {
            result.compute(leader, (k, v) -> v == null ? poolWithBonus - assigned : v + poolWithBonus - assigned);
        }
        return result;
    }
}
