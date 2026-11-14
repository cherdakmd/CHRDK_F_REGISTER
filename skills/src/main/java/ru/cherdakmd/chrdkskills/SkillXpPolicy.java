package ru.cherdakmd.chrdkskills;

import ru.cherdakmd.chrdkregister.api.RankBonus;

/** Баланс расходников без Bukkit: ограниченная прокачка, а не замена игровых действий. */
final class SkillXpPolicy {
    static final long BASE_XP = 250L;
    static final int BASE_DAILY_USES = 2;
    static final int MAX_DAILY_USES = 8;

    /** Базовый шанс дропа зелья с убийства (0,22%). */
    static final double DROP_BASE = 0.0022;
    /** Мягкий pity: +0,028% за каждое убийство без дропа. */
    static final double DROP_STEP = 0.00028;
    /** Жёсткий pity: гарантированный дроп на 145-м убийстве без дропа. */
    static final long PITY_KILLS = 145;
    /** Потолок шанса с учётом тира моба и ранга. */
    static final double DROP_CAP = 0.03;

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

    /** Серия без дропа достигла жёсткого pity — зелье гарантировано. */
    static boolean pityReached(long streak) {
        return streak >= PITY_KILLS;
    }

    /**
     * Шанс дропа с одного убийства: (база + серия×шаг) × тир моба × ранг,
     * с потолком {@link #DROP_CAP}. Тир: 1,0 обычный, 1,5 сильный, 2,0 босс.
     */
    static double dropChance(long streak, double mobTier, RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        double chance = (DROP_BASE + Math.max(0L, streak) * DROP_STEP) * Math.max(1.0, mobTier);
        chance *= 1.0 + safe.level() * 0.10;
        return Math.min(DROP_CAP, chance);
    }
}
