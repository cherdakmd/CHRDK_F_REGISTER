package ru.cherdakmd.chrdkclasses;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Активные умения классов (1.4.173, второе умение — 1.4.175).
 *
 * <p>До 1.4.173 класс был только набором пассивных прибавок: выбор влиял на
 * цифры, но не на то, как проходит бой. Умение делает класс узнаваемым — воин
 * врывается в толпу, стрелок бьёт веером, маг замораживает стаю, жрец лечит
 * группу.</p>
 *
 * <p>В 1.4.175 у каждого класса появилось <b>второе</b> умение со своим откатом
 * и своей кнопкой в меню. Пара строится по одному принципу: первое — быстрый
 * «рабочий» приём на 18–25 секунд, второе — редкий «решающий» на 30–40. Второе
 * не сильнее первого в пересчёте на секунду: это другой ход, а не вторая
 * бесплатная кнопка.</p>
 *
 * <p>Баланс, как и деревья, живёт в коде: кулдаун и величины — часть баланса,
 * конфиг может только выключить умения и растянуть кулдаун. Умение не заменяет
 * бой: урон одного применения меньше трёх ударов мечом, а лечение — меньше
 * одного зелья. Ни одно умение не тратит и не даёт валюту и не трогает блоки.</p>
 *
 * <p>Значения {@code radius} и {@code power} толкуются по-разному, поэтому
 * смысл описан у каждой константы. Тексты — {@code ability.<ключ>.name} и
 * {@code ability.<ключ>.desc} в {@code messages.yml}; ключ — {@link #key()}:
 * ид класса у первого умения и {@code ид-2} у второго.</p>
 */
public enum ClassAbility {

    /**
     * Воин, слот 1: рывок вперёд. {@code power} — множитель скорости рывка,
     * {@code radius} не используется. Плюс три секунды стойкости.
     */
    WARRIOR(PlayerClass.WARRIOR, 1, "IRON_AXE", 20, 0.0, 1.15),

    /**
     * Воин, слот 2: боевой клич. {@code radius} — радиус союзников,
     * {@code power} — полусердца поглощения себе и им. Восемь секунд.
     */
    WARRIOR_CRY(PlayerClass.WARRIOR, 2, "BELL", 40, 6.0, 4.0),

    /**
     * Стрелок, слот 1: залп из трёх стрел веером. {@code power} — урон каждой
     * стрелы, {@code radius} не используется.
     */
    ARCHER(PlayerClass.ARCHER, 1, "ARROW", 18, 0.0, 3.5),

    /**
     * Стрелок, слот 2: прицельный выстрел. Одна пробивающая стрела,
     * {@code power} — её урон, {@code radius} не используется.
     */
    ARCHER_AIM(PlayerClass.ARCHER, 2, "SPECTRAL_ARROW", 32, 0.0, 6.0),

    /**
     * Маг, слот 1: ледяная вспышка вокруг себя. {@code radius} — радиус,
     * {@code power} — урон каждому врагу и три секунды замедления.
     */
    MAGE(PlayerClass.MAGE, 1, "PACKED_ICE", 22, 6.0, 3.0),

    /**
     * Маг, слот 2: испепеление конусом вперёд. {@code radius} — длина конуса,
     * {@code power} — урон каждому врагу в нём и три секунды огня.
     */
    MAGE_SCORCH(PlayerClass.MAGE, 2, "FIRE_CHARGE", 30, 5.5, 4.0),

    /**
     * Жрец, слот 1: исцеление себя и ближайших игроков. {@code radius} — радиус,
     * {@code power} — лечение в полусердцах.
     */
    CLERIC(PlayerClass.CLERIC, 1, "GOLDEN_CARROT", 25, 8.0, 6.0),

    /**
     * Жрец, слот 2: оберег. {@code radius} — радиус союзников, {@code power} —
     * полусердца поглощения. Не лечит: это защита до удара, а не после.
     */
    CLERIC_WARD(PlayerClass.CLERIC, 2, "SHIELD", 40, 8.0, 4.0);

    /** Границы множителя кулдауна из конфига. */
    public static final double MIN_MULTIPLIER = 0.5;
    public static final double MAX_MULTIPLIER = 3.0;
    /** Первое умение класса — быстрый приём. */
    public static final int FIRST = 1;
    /** Второе умение класса — редкий приём. */
    public static final int SECOND = 2;
    /** Сколько умений у класса: ровно два, оба на отдельных кнопках. */
    public static final int PER_CLASS = 2;

    private static final Map<PlayerClass, ClassAbility> BY_CLASS = new EnumMap<>(PlayerClass.class);
    private static final Map<PlayerClass, ClassAbility> SECOND_BY_CLASS =
            new EnumMap<>(PlayerClass.class);

    static {
        for (ClassAbility ability : values()) {
            if (ability.slot == FIRST) {
                BY_CLASS.put(ability.playerClass, ability);
            } else {
                SECOND_BY_CLASS.put(ability.playerClass, ability);
            }
        }
    }

    private final PlayerClass playerClass;
    private final int slot;
    private final String iconName;
    private final int cooldownSeconds;
    private final double radius;
    private final double power;

    ClassAbility(PlayerClass playerClass, int slot, String iconName, int cooldownSeconds,
                 double radius, double power) {
        this.playerClass = playerClass;
        this.slot = slot;
        this.iconName = iconName;
        this.cooldownSeconds = cooldownSeconds;
        this.radius = radius;
        this.power = power;
    }

    public PlayerClass playerClass() {
        return playerClass;
    }

    /** Слот умения: 1 или 2. */
    public int slot() {
        return slot;
    }

    /** Ид класса. */
    public String id() {
        return playerClass.id();
    }

    /** Ключ текстов {@code ability.<ключ>.*}: {@code warrior} и {@code warrior-2}. */
    public String key() {
        return slot == FIRST ? playerClass.id() : playerClass.id() + "-" + slot;
    }

    /** Имя материала иконки: разбирается в GUI, модель Bukkit не тянет. */
    public String iconName() {
        return iconName;
    }

    /** Кулдаун без множителя, секунд. */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * Кулдаун с множителями из конфига и ранга; всегда не меньше секунды.
     *
     * <p>Ранговый множитель приходит отдельно ({@link RankPerks#cooldownFactor()}),
     * потому что это разные вещи: конфиг — настройка сервера, ранг — плюшка
     * игрока. Оба зажимаются здесь, чтобы ни один не сделал умение мгновенным.</p>
     */
    public long cooldownMillis(double multiplier) {
        return cooldownMillis(multiplier, 1.0);
    }

    public long cooldownMillis(double multiplier, double rankFactor) {
        double safe = Math.clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
        double rank = Math.clamp(rankFactor, RankPerks.MIN_COOLDOWN_FACTOR, 1.0);
        return Math.max(1_000L, Math.round(cooldownSeconds * 1_000.0 * safe * rank));
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

    /** Первое умение класса или {@code null}, если класс не выбран. */
    public static ClassAbility of(PlayerClass playerClass) {
        return playerClass == null ? null : BY_CLASS.get(playerClass);
    }

    /** Умение слота 1 или 2; мусор в слоте читается как первое. */
    public static ClassAbility of(PlayerClass playerClass, int slot) {
        if (playerClass == null) {
            return null;
        }
        return slot == SECOND ? SECOND_BY_CLASS.get(playerClass) : BY_CLASS.get(playerClass);
    }

    /** Оба умения класса в порядке слотов — для меню и сводки. */
    public static List<ClassAbility> both(PlayerClass playerClass) {
        List<ClassAbility> out = new ArrayList<>(PER_CLASS);
        ClassAbility first = of(playerClass, FIRST);
        ClassAbility second = of(playerClass, SECOND);
        if (first != null) {
            out.add(first);
        }
        if (second != null) {
            out.add(second);
        }
        return out;
    }
}
