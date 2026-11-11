package ru.cherdakmd.chrdkskills;

import ru.cherdakmd.chrdkregister.api.RankBonus;

/** Баланс расходников без Bukkit: ограниченная прокачка, а не замена игровых действий. */
final class SkillXpPolicy {
    static final long BASE_XP = 250L;
    static final int BASE_DAILY_USES = 2;
    static final int MAX_DAILY_USES = 8;

    private SkillXpPolicy() { }

    static int dailyUses(int totalLevel, int totalPrestige, RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        int progression = Math.max(0, totalLevel) / 200 + Math.max(0, totalPrestige) / 10;
        return Math.min(MAX_DAILY_USES, BASE_DAILY_USES + progression + safe.level());
    }

    static long xp(int totalPrestige, RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        double prestigeFactor = 1.0 + Math.min(0.50, Math.max(0, totalPrestige) * 0.01);
        return Math.max(1L, Math.round(BASE_XP * prestigeFactor * safe.xpFactor()));
    }

    static double dropChance(RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        return Math.min(0.01, 0.002 * (1.0 + safe.level() * 0.10));
    }
}
