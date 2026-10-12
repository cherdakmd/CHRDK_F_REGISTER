package ru.cherdakmd.chrdkgoals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Активный экземпляр цели одной волны: объём, насколько набито, флаг завершения и
 * вклад игроков (ключ — ник в нижнем регистре).
 */
public final class GoalState {

    public String id;
    public int target;
    public int rewardPool;
    public int progress;
    public boolean done;
    public final Map<String, Integer> contributions = new LinkedHashMap<>();

    public GoalState(String id, int target, int rewardPool) {
        this.id = id;
        this.target = target;
        this.rewardPool = rewardPool;
    }

    public int percent() {
        if (target <= 0) {
            return 100;
        }
        return (int) Math.min(100, Math.round(progress * 100.0 / target));
    }

    public String leader() {
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> entry : contributions.entrySet()) {
            if (best == null || entry.getValue() > best.getValue()) {
                best = entry;
            }
        }
        return best == null ? "—" : best.getKey() + " (" + best.getValue() + ")";
    }

    public int contributionOf(String playerName) {
        if (playerName == null) {
            return 0;
        }
        return contributions.getOrDefault(playerName.toLowerCase(java.util.Locale.ROOT), 0);
    }
}
