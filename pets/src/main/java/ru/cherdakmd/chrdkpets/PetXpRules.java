package ru.cherdakmd.chrdkpets;

/**
 * Множители опыта питомца — чистые правила без Bukkit.
 *
 * <p>Складываются четыре независимых условия: забота (сытость и настроение),
 * близость хозяина, ночь и выходные. Каждое задаётся в конфиге и упирается в
 * потолок; произведение ограничено сверху, чтобы опечатка в конфиге не
 * превратила прокачку в мгновенную. Опыт — единственное, что ускоряют
 * множители: бонусы питомца зависят от уровня, а не от скорости его набора.</p>
 */
public final class PetXpRules {

    /** Значения бонусов из конфига. */
    public record Factors(double near, double night, double weekend) {

        public Factors {
            near = clamp(near, 1.0, 3.0);
            night = clamp(night, 1.0, 3.0);
            weekend = clamp(weekend, 1.0, 3.0);
        }

        public static final Factors NONE = new Factors(1.0, 1.0, 1.0);
    }

    /** Потолок произведения: даже все бонусы вместе дают не больше ×5. */
    public static final double MAX_MULTIPLIER = 5.0;

    private PetXpRules() {
    }

    /**
     * Итоговый множитель опыта.
     *
     * @param wolf          волк ли это (ночной бонус может быть только ему)
     * @param care          текущие шкалы заботы
     * @param near          хозяин ближе {@code xp.near-radius}
     * @param night         ночь в мире питомца
     * @param weekend       сегодня день из {@code xp.weekend-days}
     * @param nightWolfOnly ночной бонус положен только волку
     * @param factors       значения бонусов
     */
    public static double multiplier(boolean wolf, PetCare.State care, boolean near, boolean night,
                                    boolean weekend, boolean nightWolfOnly, Factors factors) {
        Factors safe = factors == null ? Factors.NONE : factors;
        double result = PetCare.xpMultiplier(care);
        if (near) {
            result *= safe.near();
        }
        if (night && (!nightWolfOnly || wolf)) {
            result *= safe.night();
        }
        if (weekend) {
            result *= safe.weekend();
        }
        return PetCare.round(Math.min(MAX_MULTIPLIER, Math.max(0.1, result)), 4);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
