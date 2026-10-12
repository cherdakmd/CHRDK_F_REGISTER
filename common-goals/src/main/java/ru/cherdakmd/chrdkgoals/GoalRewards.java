package ru.cherdakmd.chrdkgoals;

import java.util.LinkedHashMap;
import java.util.Map;

/** Делёж пула наград между участниками: пропорционально вкладу, минимум 1 каждому. */
public final class GoalRewards {

    private GoalRewards() {
    }

    /**
     * Делёж пула: каждому участнику — минимум 1 (резерв), остаток — пропорционально
     * вкладам; недобитый хвост из-за округлений дотягиваем лидеру. Если участников
     * больше, чем пула, — выдаётся по одному самым старшим вкладчикам, пока пул
     * не кончится. Сумма выданного всегда равна min(пул с бонусом, расчётной норме),
     * т.е. пул раздаётся полностью и не превышает лимита.
     */
    public static Map<String, Integer> distribute(int pool, Map<String, Integer> contributions,
                                                  int bonusPercent) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (pool <= 0 || contributions == null || contributions.isEmpty()) {
            return result;
        }
        int poolWithBonus = (int) Math.round(pool * (1 + Math.max(0, bonusPercent) / 100.0));
        // сортируем по убыванию вклада: лидер — первый
        List<Map.Entry<String, Integer>> ordered = contributions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(java.util.Comparator.reverseOrder()))
                .toList();
        int total = ordered.stream().mapToInt(Map.Entry::getValue).sum();
        if (total <= 0) {
            return result;
        }
        if (ordered.size() >= poolWithBonus) {
            // пул меньше числа участников: по одному только старшим вкладчикам
            for (int i = 0; i < poolWithBonus; i++) {
                result.put(ordered.get(i).getKey(), 1);
            }
            return result;
        }
        // резерв минималок — по 1 каждому, остальное — пропорционально
        int rest = poolWithBonus - ordered.size();
        int assigned = 0;
        for (Map.Entry<String, Integer> entry : ordered) {
            int share = 1 + (int) Math.floor(rest * (double) entry.getValue() / total);
            result.put(entry.getKey(), share);
            assigned += share;
        }
        if (assigned < poolWithBonus) {
            String leader = ordered.get(0).getKey();
            result.merge(leader, poolWithBonus - assigned, Integer::sum);
        }
        return result;
    }
}
