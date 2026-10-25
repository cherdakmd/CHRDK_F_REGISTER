package ru.cherdakmd.chrdkartifacts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Уникальные свойства артефактов (волна 19, 1.4.129): 15 полезных «▲» и
 * 15 вредных «▼».
 *
 * <p>Зачем: каталог артефактов — это 65 фиксированных предметов, и два
 * «Камня стойкости» всегда были одинаковыми. Свойства дают вариативность:
 * каждый выпавший артефакт получает 0–2 свойства, уровень I–III, и один и тот
 * же ид может оказаться как с «Сильным захватом», так и с «Тяжёлым грузом».</p>
 *
 * <p>Класс чистый: Bukkit-типов нет, поэтому и таблица, и бросок проверяются
 * юнит-тестами. Рантайм (модификаторы и зельевые эффекты) — в
 * {@link ArtifactTraitService}, хранение — в PDC предмета.</p>
 */
public final class ArtifactTraits {

    /** Полярность свойства: польза или вред. Маркер идёт в лор предмета. */
    public enum Polarity {
        GOOD("▲", "trait-good"),
        BAD("▼", "trait-bad");

        private final String marker;
        private final String labelKey;

        Polarity(String marker, String labelKey) {
            this.marker = marker;
            this.labelKey = labelKey;
        }

        public String marker() {
            return marker;
        }

        public String labelKey() {
            return labelKey;
        }

        public boolean good() {
            return this == GOOD;
        }
    }

    /**
     * Куда ложится свойство. Атрибутные свойства применяются транзитными
     * модификаторами к игроку (артефакт носят в инвентаре, а не надевают),
     * {@link #POTION} — зельевым эффектом с пульсом.
     */
    public enum Kind {
        ATTACK_DAMAGE(true),
        ATTACK_SPEED(true),
        ARMOR(true),
        MOVEMENT_SPEED(true),
        MAX_HEALTH(false),
        LUCK(false),
        KNOCKBACK_RESISTANCE(false),
        OXYGEN(false),
        SAFE_FALL(false),
        BLOCK_BREAK_SPEED(true),
        SNEAKING_SPEED(true),
        REACH(false),
        BURNING_TIME(true),
        POTION(false);

        private final boolean percent;

        Kind(boolean percent) {
            this.percent = percent;
        }

        /** Проценты (multiply_scalar_1) или целые единицы (add_number). */
        public boolean percent() {
            return percent;
        }
    }

    /**
     * Описание свойства.
     *
     * @param id         ключ в PDC и messages.yml ({@code trait.<id>})
     * @param polarity   польза/вред
     * @param kind       атрибут или зельевой эффект
     * @param values     величина по уровням I..III (у зельевых — уровень эффекта)
     * @param potionType строковой тип зельевого эффекта (null для атрибутных)
     * @param amplifiers амплификаторы эффекта по уровням (null для атрибутных)
     */
    public record Def(String id, Polarity polarity, Kind kind, double[] values,
                      String potionType, int[] amplifiers) {

        public int maxLevel() {
            return values.length;
        }

        /** Величина на уровне (уровни выше потолка держат последнюю ступень). */
        public double value(int level) {
            return values[Math.min(Math.max(1, level), values.length) - 1];
        }

        /** Амплификатор зельевого эффекта (0 — I уровень). */
        public int amplifier(int level) {
            if (amplifiers == null || amplifiers.length == 0) {
                return 0;
            }
            return amplifiers[Math.min(Math.max(1, level), amplifiers.length) - 1];
        }
    }

    /** Выпавшее свойство: id и уровень I..III. */
    public record Roll(String id, int level) {
    }

    private static final int SECOND_ROLL_PERCENT = 35;
    private static final int HARD_MAX_PER_ITEM = 3;
    private static final int HARD_MAX_LEVEL = 3;

    private static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    static {
        /* ---------------------------- 15 полезных свойств ▲ ---------------------------- */
        good("silnyj-zahvat", Kind.ATTACK_DAMAGE, 4, 6, 8);
        good("tverdost-kozh", Kind.ARMOR, 5, 7, 9);
        good("legkij-shag", Kind.MOVEMENT_SPEED, 3, 4, 5);
        good("bytroe-serdce", Kind.ATTACK_SPEED, 4, 7, 10);
        good("zapas-sil", Kind.MAX_HEALTH, 1, 1, 2);
        good("udacha-talismana", Kind.LUCK, 1, 1, 2);
        good("ustojchivost", Kind.KNOCKBACK_RESISTANCE, 0.05, 0.08, 0.12);
        good("glubokoe-dyhanie", Kind.OXYGEN, 1, 2, 3);
        good("myagkoe-padenie", Kind.SAFE_FALL, 0.5, 1.0, 1.5);
        good("bystraya-kopka", Kind.BLOCK_BREAK_SPEED, 5, 8, 12);
        good("tihaya-postup", Kind.SNEAKING_SPEED, 10, 15, 20);
        potion("regeneraciya", Polarity.GOOD, "regeneration", new int[]{0, 0, 1});
        potion("noschnoe-zrenie", Polarity.GOOD, "night_vision", new int[]{0, 0, 0});
        potion("plavuchest", Polarity.GOOD, "water_breathing", new int[]{0, 0, 0});
        potion("ognezashchita", Polarity.GOOD, "fire_resistance", new int[]{0, 0, 0});

        /* ----------------------------- 15 вредных свойств ▼ ---------------------------- */
        bad("tyazhelyj-gruz", Kind.MOVEMENT_SPEED, 3, 5, 7);
        bad("slabaya-ruka", Kind.ATTACK_DAMAGE, 4, 6, 8);
        bad("zamedlennyj-zamah", Kind.ATTACK_SPEED, 5, 8, 12);
        bad("hrupkaya-oprava", Kind.ARMOR, 4, 6, 8);
        bad("slaboe-serdce", Kind.MAX_HEALTH, 1, 1, 2);
        bad("zlye-ogljadki", Kind.LUCK, 1, 1, 2);
        bad("korotkaya-ruka", Kind.REACH, 0.2, 0.35, 0.5);
        badRaw("zharkoe-plamya", Kind.BURNING_TIME, 15, 25, 35);
        bad("vysokoe-padenie", Kind.SAFE_FALL, 0.5, 1.0, 1.5);
        bad("dolgaya-kopka", Kind.BLOCK_BREAK_SPEED, 5, 8, 12);
        bad("skolzkij-shag", Kind.SNEAKING_SPEED, 10, 15, 20);
        potion("tormoz", Polarity.BAD, "slowness", new int[]{0, 0, 1});
        potion("slabost", Polarity.BAD, "weakness", new int[]{0, 0, 0});
        potion("tyazhelye-ruki", Polarity.BAD, "mining_fatigue", new int[]{0, 0, 0});
        potion("vechnyj-golod", Polarity.BAD, "hunger", new int[]{0, 0, 1});
    }

    private ArtifactTraits() {
    }

    private static void good(String id, Kind kind, double... values) {
        REGISTRY.put(id, new Def(id, Polarity.GOOD, kind, values, null, null));
    }

    private static void bad(String id, Kind kind, double... values) {
        double[] negative = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            negative[i] = -Math.abs(values[i]);
        }
        REGISTRY.put(id, new Def(id, Polarity.BAD, kind, negative, null, null));
    }

    /**
     * Вредное свойство, у которого минус выражается положительной величиной:
     * атрибут «время горения» растёт — и игрок горит дольше.
     */
    private static void badRaw(String id, Kind kind, double... values) {
        REGISTRY.put(id, new Def(id, Polarity.BAD, kind, values, null, null));
    }

    private static void potion(String id, Polarity polarity, String type, int[] amplifiers) {
        REGISTRY.put(id, new Def(id, polarity, Kind.POTION,
                new double[]{1, 1, 1}, type, amplifiers));
    }

    public static Def byId(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(Locale.ROOT).trim());
    }

    public static List<Def> all() {
        return List.copyOf(REGISTRY.values());
    }

    public static List<Def> good() {
        return filter(Polarity.GOOD);
    }

    public static List<Def> bad() {
        return filter(Polarity.BAD);
    }

    public static int size() {
        return REGISTRY.size();
    }

    private static List<Def> filter(Polarity polarity) {
        List<Def> result = new ArrayList<>();
        for (Def def : REGISTRY.values()) {
            if (def.polarity() == polarity) {
                result.add(def);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Бросок свойств при создании артефакта.
     *
     * <p>Сначала бросается {@code chancePercent}: не прошёл — предмет «чистый».
     * Затем 1 свойство и с шансом {@value #SECOND_ROLL_PERCENT}% каждое
     * следующее, но не больше {@code maxPerItem} и не больше трёх. Полярность
     * задаётся стороной артефакта; {@code crossPercent} — шанс «чужого» свойства
     * (изъян на светлом, искра на проклятом).</p>
     *
     * @param side             сторона артефакта
     * @param random           генератор
     * @param chancePercent    0..100 — шанс получить хоть одно свойство
     * @param maxPerItem       0..3
     * @param maxLevel         1..3
     * @param crossPercent     0..100 — шанс противоположной полярности
     * @return свойства в порядке выдачи (пусто — предмет чистый)
     */
    public static List<Roll> roll(ArtifactDef.Side side, Random random, int chancePercent,
                                  int maxPerItem, int maxLevel, int crossPercent) {
        if (side == null || random == null || chancePercent <= 0 || maxPerItem <= 0) {
            return List.of();
        }
        if (random.nextInt(100) >= chancePercent) {
            return List.of();
        }
        int limit = Math.min(maxPerItem, HARD_MAX_PER_ITEM);
        int cap = Math.min(Math.max(1, maxLevel), HARD_MAX_LEVEL);
        int wanted = 1;
        while (wanted < limit && random.nextInt(100) < SECOND_ROLL_PERCENT) {
            wanted++;
        }
        List<Roll> result = new ArrayList<>(wanted);
        List<String> used = new ArrayList<>(wanted);
        for (int i = 0; i < wanted; i++) {
            Polarity wantedPolarity = side == ArtifactDef.Side.GOOD
                    ? Polarity.GOOD : Polarity.BAD;
            if (crossPercent > 0 && random.nextInt(100) < crossPercent) {
                wantedPolarity = wantedPolarity == Polarity.GOOD ? Polarity.BAD : Polarity.GOOD;
            }
            List<Def> pool = new ArrayList<>(filter(wantedPolarity));
            pool.removeIf(def -> used.contains(def.id()));
            if (pool.isEmpty()) {
                // редкий случай: все свойства этой полярности уже выпали — пробуем другую
                pool = new ArrayList<>(filter(wantedPolarity == Polarity.GOOD
                        ? Polarity.BAD : Polarity.GOOD));
                pool.removeIf(def -> used.contains(def.id()));
            }
            if (pool.isEmpty()) {
                break;
            }
            Collections.shuffle(pool, random);
            Def picked = pool.get(0);
            int level = 1 + random.nextInt(Math.max(1, Math.min(cap, picked.maxLevel())));
            result.add(new Roll(picked.id(), level));
            used.add(picked.id());
        }
        return List.copyOf(result);
    }

    /** Максимальный уровень свойства в каталоге (для превью/подсказок). */
    public static int hardMaxLevel() {
        return HARD_MAX_LEVEL;
    }
}
