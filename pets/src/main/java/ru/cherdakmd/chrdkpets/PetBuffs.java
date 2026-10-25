package ru.cherdakmd.chrdkpets;

/**
 * Бонусы питомца и его хозяина — чистая арифметика без Bukkit.
 *
 * <p>Все величины задаются «на уровень выше первого» и упираются в жёсткий
 * потолок из конфига. Потолки здесь не декорация: именно они не дают
 * администратору (или опечатке в конфиге) превратить волка в рейд-босса, а
 * кошку — в источник бесконечной удачи. Значения по умолчанию для 10-го
 * уровня:</p>
 *
 * <ul>
 *   <li>волк — +18 HP, +2.25 урона, +3.6 % скорости, хозяину +2.7 % скорости;</li>
 *   <li>кошка — +9 HP, +3.15 % скорости, хозяину +0.9 удачи.</li>
 * </ul>
 *
 * <p>Бонусы хозяина — «аура»: работают, только пока питомец жив и находится
 * рядом (радиус в конфиге), и не складываются от нескольких питомцев — аура
 * берётся от ближайшего.</p>
 */
public final class PetBuffs {

    /**
     * Настройки одного вида. Все поля клампятся при разборе конфига: отрицательное
     * значение — это ноль, а не «минус к здоровью».
     */
    public record Settings(double healthPerLevel, double maxHealthBonus,
                           double damagePerLevel, double maxDamageBonus,
                           double speedPercentPerLevel, double maxSpeedPercent,
                           double ownerSpeedPercentPerLevel, double maxOwnerSpeedPercent,
                           double ownerLuckPerLevel, double maxOwnerLuck) {

        public Settings {
            healthPerLevel = clamp(healthPerLevel, 0.0, 40.0);
            maxHealthBonus = clamp(maxHealthBonus, 0.0, 200.0);
            damagePerLevel = clamp(damagePerLevel, 0.0, 20.0);
            maxDamageBonus = clamp(maxDamageBonus, 0.0, 100.0);
            speedPercentPerLevel = clamp(speedPercentPerLevel, 0.0, 20.0);
            maxSpeedPercent = clamp(maxSpeedPercent, 0.0, 100.0);
            ownerSpeedPercentPerLevel = clamp(ownerSpeedPercentPerLevel, 0.0, 20.0);
            maxOwnerSpeedPercent = clamp(maxOwnerSpeedPercent, 0.0, 50.0);
            ownerLuckPerLevel = clamp(ownerLuckPerLevel, 0.0, 5.0);
            maxOwnerLuck = clamp(maxOwnerLuck, 0.0, 10.0);
        }

        public static final Settings NONE = new Settings(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Итоговые бонусы на конкретном уровне.
     *
     * <p>{@code health} и {@code damage} — абсолютные числа, {@code speedScalar}
     * и {@code ownerSpeedScalar} — доли для {@code MULTIPLY_SCALAR_1}
     * (0.036 = +3.6 %), {@code ownerLuck} — абсолютная удача.</p>
     */
    public record Stats(double health, double damage, double speedScalar,
                        double ownerSpeedScalar, double ownerLuck) {

        public static final Stats NONE = new Stats(0, 0, 0, 0, 0);

        /** Есть ли у хозяина хоть какой-то бонус от этого уровня. */
        public boolean hasOwnerBonus() {
            return ownerSpeedScalar > 1.0e-6 || ownerLuck > 1.0e-6;
        }
    }

    private PetBuffs() {
    }

    /** Бонусы вида на уровне {@code level} (1 — ровно ноль). */
    public static Stats stats(Settings settings, int level) {
        if (settings == null) {
            return Stats.NONE;
        }
        int steps = Math.max(0, level - 1);
        double health = Math.min(settings.maxHealthBonus(), settings.healthPerLevel() * steps);
        double damage = Math.min(settings.maxDamageBonus(), settings.damagePerLevel() * steps);
        double speed = Math.min(settings.maxSpeedPercent(), settings.speedPercentPerLevel() * steps) / 100.0;
        double ownerSpeed = Math.min(settings.maxOwnerSpeedPercent(),
                settings.ownerSpeedPercentPerLevel() * steps) / 100.0;
        double ownerLuck = Math.min(settings.maxOwnerLuck(), settings.ownerLuckPerLevel() * steps);
        return new Stats(round(health, 3), round(damage, 3), round(speed, 5),
                round(ownerSpeed, 5), round(ownerLuck, 3));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
