package ru.cherdakmd.chrdkbp;

import java.util.Map;

/** Permanent earned ranks depend on completing the main lane, not buying or consuming boost hours. */
final class BattlePassExpeditionRules {
    private BattlePassExpeditionRules() { }
    static boolean complete(BattlePassConfig.Season season, Map<String, Integer> progress, long now) {
        if (season == null || season.empty() || progress == null) return false;
        for (int i = 0; i < season.weeks().size(); i++) {
            if (!BattlePassConfig.isUnlocked(season, i, now)) return false;
            for (var m : season.weeks().get(i).missions())
                if (m.target() <= 0 || progress.getOrDefault(m.id(), 0) < m.target()) return false;
        }
        return true;
    }
}
