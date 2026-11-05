package ru.cherdakmd.chrdkskills.tree;

/** Числа каркаса: токены, тир-гейты, цена уровня и потолок пыли. */
public final class PerkTreeMath {

    /** Один токен за каждые 10 уровней домена. */
    public static final int LEVELS_PER_TOKEN = 10;
    /** Каждый уровень ядра даёт дополнительный токен. */
    public static final int CORE_TOKENS_PER_LEVEL = 1;
    /** Потолок цены одного уровня узла, чтобы формула не уходила в миллионы. */
    public static final long MAX_LEVEL_POWDER_COST = 100_000L;

    private PerkTreeMath() {
    }

    /** Сколько токенов заработаноlevels доменом и ядром. */
    public static int tokensEarned(int level, int coreLevels) {
        return Math.max(0, level) / LEVELS_PER_TOKEN
                + Math.max(0, coreLevels) * CORE_TOKENS_PER_LEVEL;
    }

    /** Самый старший тир, доступный при текущем уровне домена. */
    public static int availableTier(int level, int tiers) {
        return Math.min(tiers, 1 + Math.max(0, level) / LEVELS_PER_TOKEN);
    }

    /** Цена следующего уровня узла: база × (следующий уровень)^2, с потолком. */
    public static long levelCost(PerkNode node, int currentLevel) {
        long next = Math.max(1L, (long) currentLevel + 1L);
        return Math.min(MAX_LEVEL_POWDER_COST, node.powderCost() * next * next);
    }

    /** Итоговая величина эффекта узла на текущем уровне. */
    public static double effectValue(PerkNode node, int level) {
        return node.value() * Math.max(0, level);
    }

    /** Потолок запаса пыли: сколько реально зачислить сверх уже накопленного. */
    public static long creditedPowder(long currentPowder, long earned, long dailyCap) {
        if (earned <= 0 || dailyCap <= 0) {
            return 0L;
        }
        return Math.max(0L, Math.min(earned, dailyCap - Math.max(0L, currentPowder)));
    }
}
