package ru.cherdakmd.chrdktreasures;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Склянки опыта в каждом кладе (1.4.157).
 *
 * <p>Опыт — единственная награда, которая не занимает место в сундуке и не
 * обесценивает экономику алмазов, поэтому склянки выдаются <b>всегда</b>:
 * клад без опыта ощущался бы как пустая порода. Количество зависит от
 * редкости, изредка удваивается и всегда зажато потолком.</p>
 *
 * <p>Правило живёт в {@code config.yml} ({@code experience.*}), но не в
 * {@code loot.yml}: лут-таблицы администратор правит под свой сервер, и
 * «часто» не должно зависеть от того, обновился ли файл. Поэтому бросок
 * склянок идёт отдельным шагом поверх любой таблицы.</p>
 *
 * <p>Класс чистый: ни Bukkit, ни {@code Messages} — количества проверяются
 * юнит-тестами без сервера.</p>
 */
public final class TreasureExperience {

    /** Материал склянки: обычная «склянка опыта» Minecraft. */
    public static final String MATERIAL = "EXPERIENCE_BOTTLE";

    /** Потолок склянок за один клад: больше в инвентарь новичка не влезет. */
    public static final int MAX_PER_TREASURE = 64;

    /** Потолок диапазона одной редкости. */
    public static final int MAX_PER_TIER = 32;

    private TreasureExperience() {
    }

    /**
     * Диапазон склянок для одной редкости.
     *
     * <p>Значения зажимаются в конструкторе: опечатка в конфиге
     * ({@code max: 5000}) не должна выдать игроку сундук опыта.</p>
     */
    public record Range(int min, int max) {

        public Range {
            min = Math.clamp(min, 0, MAX_PER_TIER);
            max = Math.clamp(max, min, MAX_PER_TIER);
        }

        /** Ничего не выдавать — законное значение для редкости. */
        public static final Range NONE = new Range(0, 0);

        /** Сколько склянок выпадет по этому диапазону. */
        public int roll(Random random) {
            if (max <= 0) {
                return 0;
            }
            if (max == min) {
                return min;
            }
            if (random == null) {
                return min;
            }
            return min + random.nextInt(max - min + 1);
        }
    }

    /**
     * Правило выдачи опыта.
     *
     * @param enabled             включены ли склянки вообще
     * @param ranges              диапазон по редкостям
     * @param bonusChancePercent  шанс, что склянок будет больше обычного, 0..100
     * @param bonusMultiplierPercent на сколько процентов больше при срабатывании бонуса
     * @param maxPerTreasure      потолок склянок за один клад, 1..{@value #MAX_PER_TREASURE}
     */
    public record Rule(boolean enabled, Map<TreasureTier, Range> ranges,
                       int bonusChancePercent, int bonusMultiplierPercent,
                       int maxPerTreasure) {

        public Rule {
            Map<TreasureTier, Range> fallback = defaultRanges();
            Map<TreasureTier, Range> copy = new EnumMap<>(TreasureTier.class);
            for (TreasureTier tier : TreasureTier.values()) {
                Range range = ranges == null ? null : ranges.get(tier);
                copy.put(tier, range == null ? fallback.get(tier) : range);
            }
            ranges = Collections.unmodifiableMap(copy);
            bonusChancePercent = Math.clamp(bonusChancePercent, 0, 100);
            bonusMultiplierPercent = Math.clamp(bonusMultiplierPercent, 0, 400);
            maxPerTreasure = Math.clamp(maxPerTreasure, 1, MAX_PER_TREASURE);
        }

        /** Диапазон редкости (никогда не {@code null}). */
        public Range range(TreasureTier tier) {
            Range range = tier == null ? null : ranges.get(tier);
            return range == null ? Range.NONE : range;
        }
    }

    /**
     * Умолчания: обычный клад даёт 1–2 склянки, мифический — 8–14, каждый
     * четвёртый клад приносит вдвое больше. Потолок 32 — это уже 96–352 опыта
     * за один клад, дальше начинается «бесплатный тридцатый уровень».
     */
    public static Rule defaults() {
        return new Rule(true, defaultRanges(), 25, 100, 32);
    }

    /** Диапазоны по умолчанию: от обычного клада к мифическому. */
    public static Map<TreasureTier, Range> defaultRanges() {
        Map<TreasureTier, Range> ranges = new EnumMap<>(TreasureTier.class);
        ranges.put(TreasureTier.COMMON, new Range(1, 2));
        ranges.put(TreasureTier.RARE, new Range(2, 4));
        ranges.put(TreasureTier.EPIC, new Range(3, 6));
        ranges.put(TreasureTier.LEGENDARY, new Range(5, 9));
        ranges.put(TreasureTier.MYTHICAL, new Range(8, 14));
        return Collections.unmodifiableMap(ranges);
    }

    /** Выключенное правило: клад не даёт склянок. */
    public static Rule disabled() {
        return new Rule(false, defaults().ranges(), 0, 0, 1);
    }

    /**
     * Сколько склянок опыта даёт клад.
     *
     * @return 0..{@link Rule#maxPerTreasure()}; 0 — правило выключено
     */
    public static int roll(Rule rule, TreasureTier tier, Random random) {
        if (rule == null || !rule.enabled() || tier == null) {
            return 0;
        }
        Range range = rule.range(tier);
        int amount = range.roll(random);
        if (amount <= 0) {
            return 0;
        }
        if (rule.bonusChancePercent() > 0 && random != null
                && random.nextDouble() * 100.0 < rule.bonusChancePercent()) {
            int extra = Math.max(1, (int) Math.round(amount * (rule.bonusMultiplierPercent() / 100.0)));
            amount += extra;
        }
        return Math.min(amount, rule.maxPerTreasure());
    }
}
