package ru.cherdakmd.chrdkskills;

import ru.cherdakmd.chrdkregister.api.RankBonus;

/** Баланс расходников без Bukkit: редкие зелия дополняют, а не заменяют действия. */
final class SkillXpPolicy {
    static final long BASE_XP = 250L;

    /** Базовый шанс дропа зелья за действие (2%). */
    static final double DROP_BASE = 0.02;
    /** Мягкий pity: +0,2% за каждое действие без дропа. */
    static final double DROP_STEP = 0.002;
    /** Жёсткий pity: гарантированный дроп на 25-м действии без дропа. */
    static final long PITY_ACTIONS = 25;
    /** Потолок шанса с учётом тира моба и ранга. */
    static final double DROP_CAP = 0.05;

    private SkillXpPolicy() { }

    static long xp(int totalPrestige, RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        double prestigeFactor = 1.0 + Math.min(0.50, Math.max(0, totalPrestige) * 0.01);
        return Math.max(1L, Math.round(BASE_XP * prestigeFactor * safe.xpFactor()));
    }

    /** Серия без дропа достигла жёсткого pity — зелье гарантировано. */
    static boolean pityReached(long streak) {
        return streak >= PITY_ACTIONS;
    }

    /**
     * Шанс дропа зелья с одного действия: (база + серия×шаг) × тир × ранг,
     * с потолком {@link #DROP_CAP}. Тир: 1,0 — игровое действие (добыча,
     * рыбалка, чары…), 1,5 — сильный моб, 2,0 — босс.
     */
    static double dropChance(long streak, double tier, RankBonus rank) {
        RankBonus safe = rank == null ? RankBonus.NONE : rank;
        double chance = (DROP_BASE + Math.max(0L, streak) * DROP_STEP) * Math.max(1.0, tier);
        chance *= 1.0 + safe.level() * 0.10;
        return Math.min(DROP_CAP, chance);
    }
}
