package ru.cherdakmd.chrdkclasses;

import java.util.EnumMap;
import java.util.Map;

/**
 * Активные умения классов (1.4.173): одна способность на класс.
 *
 * <p>До этой версии класс был только набором пассивных прибавок: выбор влиял на
 * цифры, но не на то, как проходит бой. Умение делает класс узнаваемым — воин
 * врывается в толпу, стрелок бьёт веером, маг замораживает стаю, жрец лечит
 * группу.</p>
 *
 * <p>Баланс, как и деревья, живёт в коде: кулдаун и величины — часть баланса,
 * конфиг может только выключить умения и растянуть кулдаун. Умение не заменяет
 * бой: урон одного применения меньше трёх ударов мечом, а лечение — меньше
 * одного зелья. Ни одно умение не тратит и не даёт валюту.</p>
 *
 * <p>Значения {@code radius} и {@code power} толкуются по-разному, поэтому
 * смысл описан у каждой константы. Тексты — {@code ability.<ид>.name} и
 * {@code ability.<ид>.desc} в {@code messages.yml}, где ид — ид класса.</p>
 */
public enum ClassAbility {

    /**
     * Воин: рывок вперёд. {@code power} — множитель скорости рывка,
     * {@code radius} не используется. Плюс три секунды стойкости.
     */
    WARRIOR(PlayerClass.WARRIOR, "IRON_AXE", 20, 0.0, 1.15),

    /**
     * Стрелок: залп из трёх стрел веером. {@code power} — урон каждой стрелы,
     * {@code radius} не используется.
     */
    ARCHER(PlayerClass.ARCHER, "ARROW", 18, 0.0, 3.5),

    /**
     * Маг: ледяная вспышка вокруг себя. {@code radius} — радиус,
     * {@code power} — урон каждому врагу и три секунды замедления.
     */
    MAGE(PlayerClass.MAGE, "PACKED_ICE", 22, 6.0, 3.0),

    /**
     * Жрец: исцеление себя и ближайших игроков. {@code radius} — радиус,
     * {@code power} — лечение в полусердцах.
     */
    CLERIC(PlayerClass.CLERIC, "GOLDEN_CARROT", 25, 8.0, 6.0);

    /** Границы множителя кулдауна из конфига. */
    public static final double MIN_MULTIPLIER = 0.5;
    public static final double MAX_MULTIPLIER = 3.0;

    private static final Map<PlayerClass, ClassAbility> BY_CLASS = new EnumMap<>(PlayerClass.class);

    static {
        for (ClassAbility ability : values()) {
            BY_CLASS.put(ability.playerClass, ability);
        }
    }

    private final PlayerClass playerClass;
    private final String iconName;
    private final int cooldownSeconds;
    private final double radius;
    private final double power;

    ClassAbility(PlayerClass playerClass, String iconName, int cooldownSeconds, double radius,
                 double power) {
        this.playerClass = playerClass;
        this.iconName = iconName;
        this.cooldownSeconds = cooldownSeconds;
        this.radius = radius;
        this.power = power;
    }

    public PlayerClass playerClass() {
        return playerClass;
    }

    /** Ид класса — он же ключ текстов {@code ability.<ид>.*}. */
    public String id() {
        return playerClass.id();
    }

    /** Имя материала иконки: разбирается в GUI, модель Bukkit не тянет. */
    public String iconName() {
        return iconName;
    }

    /** Кулдаун без множителя, секунд. */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** Кулдаун с множителем из конфига; всегда не меньше секунды. */
    public long cooldownMillis(double multiplier) {
        double safe = Math.clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        return Math.max(1_000L, Math.round(cooldownSeconds * 1_000.0 * safe));
    }

    /**
     * Сколько миллисекунд осталось от кулдауна.
     *
     * <p>Чистая арифметика: момент последнего применения приходит из
     * {@link ClassStore}, поэтому выход и повторный вход кулдаун не сбрасывают.
     * Если системные часы ушли назад, остаток считается полным — умение в
     * такой ситуации не должно становиться бесплатным.</p>
     *
     * @param lastUseAt      момент последнего применения (0 — не применялось)
     * @param now            текущий момент
     * @param cooldownMillis полный кулдаун
     * @return остаток; 0 — умение готово
     */
    public static long remaining(long lastUseAt, long now, long cooldownMillis) {
        if (lastUseAt <= 0L || cooldownMillis <= 0L) {
            return 0L;
        }
        long elapsed = now - lastUseAt;
        if (elapsed < 0L) {
            return cooldownMillis;
        }
        return Math.max(0L, cooldownMillis - elapsed);
    }

    public double radius() {
        return radius;
    }

    public double power() {
        return power;
    }

    /** Умение класса или {@code null}, если класс не выбран. */
    public static ClassAbility of(PlayerClass playerClass) {
        return playerClass == null ? null : BY_CLASS.get(playerClass);
    }
}
