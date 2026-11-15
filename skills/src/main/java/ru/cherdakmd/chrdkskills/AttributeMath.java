package ru.cherdakmd.chrdkskills;

/**
 * Атрибуты персонажа: чистая математика без Bukkit.
 *
 * <p>Очки даёт прокачка: каждые 5 уровней любого навыка — одно очко, каждое
 * перерождение — ещё двадцать. Атрибуты взаимодействуют с тирами мобов тремя
 * способами: <b>порог зоны</b> (недобор атрибутов давит штрафом), <b>бонус
 * против сильных</b> (бонусы атаки сильнее против мобов выше своего ранга) и
 * <b>компенсация пробивания</b> (стойкость возвращает часть потолка защиты,
 * который отбирают высшие тиры).</p>
 */
public final class AttributeMath {

    /** Каждые столько уровней навыка дают одно очко атрибута. */
    public static final int LEVELS_PER_POINT = 5;
    /** Каждое перерождение даёт столько очков (10 старых ступеней × 2). */
    public static final int POINTS_PER_REBIRTH = 20;
    /** Очки суммы за ступень «ранга атрибутов» и порог тира. */
    public static final int SUM_PER_RANK = 20;
    /** Максимальный штраф давления тира (доля). */
    public static final double MAX_PRESSURE = 0.25;
    /** Максимальная компенсация пробивания стойкостью (доля). */
    public static final double MAX_MITIGATION_BONUS = 0.10;

    private AttributeMath() { }

    /** Всего очков за прокачку: уровни навыков и перерождения. */
    public static int points(int totalLevel, int rebirths) {
        return Math.max(0, totalLevel) / LEVELS_PER_POINT
                + POINTS_PER_REBIRTH * Math.max(0, rebirths);
    }

    /** Порог атрибутов зоны тира: с тира 6 — (тир − 5) × 20 очков суммы. */
    public static int threshold(int tier) {
        return Math.max(0, Math.max(1, tier) - 5) * SUM_PER_RANK;
    }

    /** Давление тира: штраф за недобор атрибутов, доля 0..{@link #MAX_PRESSURE}. */
    public static double pressure(int tier, int attributeSum) {
        int deficit = Math.max(0, threshold(tier) - Math.max(0, attributeSum));
        return Math.min(MAX_PRESSURE, 0.05 * ((deficit + SUM_PER_RANK - 1) / SUM_PER_RANK));
    }

    /** Ранг атрибутов игрока: каждые 20 очков суммы — ступень силы. */
    public static int attrRank(int attributeSum) {
        return Math.max(0, attributeSum) / SUM_PER_RANK;
    }

    /** Бонус против сильных: мобы тиром выше ранга усиливают бонусы атаки в 1.5 раза. */
    public static double strongFoeFactor(int mobTier, int attributeSum) {
        return mobTier > attrRank(attributeSum) ? 1.5 : 1.0;
    }

    /** Сила (+0.5%/очко) и хитрость (+0.3%/очко) в ближнем бою, с бонусом против сильных. */
    public static double meleeFactor(int strength, int cunning, int mobTier, int attributeSum) {
        double bonus = 0.005 * Math.max(0, strength) + 0.003 * Math.max(0, cunning);
        return 1.0 + bonus * strongFoeFactor(mobTier, attributeSum);
    }

    /** Хитрость в дальнем бою (+0.3%/очко), с бонусом против сильных. */
    public static double rangedFactor(int cunning, int mobTier, int attributeSum) {
        double bonus = 0.003 * Math.max(0, cunning);
        return 1.0 + bonus * strongFoeFactor(mobTier, attributeSum);
    }

    /** Мудрость: +0.5% опыта навыков за очко. */
    public static double xpFactor(int wisdom) {
        return 1.0 + 0.005 * Math.max(0, wisdom);
    }

    /** Стойкость: +0.25 здоровья (❤-половинки = 0.5 ед.) за очко. */
    public static double bonusHealth(int stamina) {
        return 0.25 * Math.max(0, stamina);
    }

    /** Стойкость: +0.5% к персональному капу снижения урона за очко, до {@link #MAX_MITIGATION_BONUS}. */
    public static double mitigationBonus(int stamina) {
        return Math.min(MAX_MITIGATION_BONUS, 0.005 * Math.max(0, stamina));
    }

    /** Ловкость: +0.25% скорости атаки за очко. */
    public static double attackSpeed(int agility) {
        return 0.0025 * Math.max(0, agility);
    }

    /** Ловкость: +0.1% скорости передвижения за очко. */
    public static double moveSpeed(int agility) {
        return 0.001 * Math.max(0, agility);
    }
}
