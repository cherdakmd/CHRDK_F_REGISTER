package ru.cherdakmd.chrdkskills;

/**
 * Чистая математика веток билда: стоимость уровней, пассивные бонусы веток и
 * масштаб активных приёмов ступенью престижа (в духе {@code 8 + 2·rank²} у
 * кирки — полная мощь на высших ступенях). Очки веток берутся из общего пула
 * с атрибутами: {@link AttributeMath#points(long, long)} минус потраченное,
 * плюс бонусы честного PvE-вклада.
 */
public final class BranchMath {

    /** Стоимость следующего уровня ветки в очках общего пула. */
    public static final int LEVEL_COST = 1;
    /** Цена полного сброса веток деньгами: штраф за метагейминг под босса. */
    static final long RESPEC_PRICE = 2_500L;
    /** Бонусное очко общего пула за каждые единиц честного PvE-вклада. */
    static final int PVE_PER_POINT = 500;
    static final int RANK_MIN = 1;
    static final int RANK_MAX = 10;

    private BranchMath() { }

    static int level(int value) {
        return Math.clamp(value, 0, BuildBranch.MAX_LEVEL);
    }

    static int rank(int prestige) {
        return Math.clamp(prestige, RANK_MIN, RANK_MAX);
    }

    /** Потрачено на ветки: каждый уровень стоит {@link #LEVEL_COST} очков. */
    static int spent(int[] levels) {
        if (levels == null) return 0;
        int total = 0;
        for (int level : levels) total += level(level) * LEVEL_COST;
        return total;
    }

    /** Воин: ближний урон +2% за уровень ветки. */
    static double meleeMult(int warriorLevel) {
        return 1.0 + 0.02 * level(warriorLevel);
    }

    /** Стрелок: дальний урон +2% за уровень ветки. */
    static double rangedMult(int archerLevel) {
        return 1.0 + 0.02 * level(archerLevel);
    }

    /** Маг: интеллект внутри ветки — опыт навыков +2% за уровень. */
    static double xpMult(int mageLevel) {
        return 1.0 + 0.02 * level(mageLevel);
    }

    /** Разбойник: +1% урона и +0.2% скорости атаки за уровень. */
    static double rogueDamageMult(int rogueLevel) {
        return 1.0 + 0.01 * level(rogueLevel);
    }

    static double rogueAttackSpeed(int rogueLevel) {
        return 0.002 * level(rogueLevel);
    }

    /** Жрец: поддержка — +1 здоровье за уровень. */
    static double priestHealth(int priestLevel) {
        return 1.0 * level(priestLevel);
    }

    /** Клирик: смягчение +0.5% за уровень, суммируется со Стойкостью. */
    public static double clericMitigation(int clericLevel) {
        return 0.005 * level(clericLevel);
    }

    /**
     * Сила активного приёма: база × престиж (квадратично) × уровень ветки.
     * На I ступени престижа приём слабый, полная мощь — на верхних.
     */
    static double power(int prestige, int branchLevel, double base) {
        int rank = rank(prestige);
        return base * (1.0 + 0.05 * rank * rank) * (1.0 + 0.05 * level(branchLevel));
    }

    /** Кулдаун приёма сокращается ступенью престижа, как у способностей навыков. */
    static int cooldown(int baseCooldown, int prestige) {
        return Math.max(8, baseCooldown - 2 * (rank(prestige) - 1));
    }
}
