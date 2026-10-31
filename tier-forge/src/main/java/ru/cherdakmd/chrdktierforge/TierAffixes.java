package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Реестр универсальных аффиксов: 15 хороших и 15 плохих, не привязанных к тиру.
 *
 * <p>Идея волны 17: свойство тира отвечает за «характер» редкости, а аффиксы
 * добавляют поверх него случайные достоинства и дефекты. Тир определяет только
 * их количество — от двух дефектов у проклятого до двух бонусов у легендарного.
 * Комбинаций получается намного больше, чем при одном свойстве: 15 свойств тира
 * × пары хороших аффиксов × возможный дефект × зачарования.</p>
 *
 * <p><b>Бросок детерминированный.</b> Аффиксы выводятся из стабильного id
 * предмета ({@code modifier_uid}), тира и вида — поэтому предпросмотр на
 * наковальне и кузнечном столе не переролливается при каждом движении слота, и
 * «порыбачить» за хорошими аффиксами нельзя. Новый тир — новый набор.</p>
 *
 * <p>Класс чистый: ни Bukkit, ни конфига — пулы, количества и бросок проверяются
 * юнит-тестами, а применяет их {@link TierItems}.</p>
 */
public final class TierAffixes {

    /** Хороших аффиксов в реестре. */
    public static final int GOOD_TOTAL = 15;
    /** Плохих аффиксов в реестре. */
    public static final int BAD_TOTAL = 15;
    /** Потолок аффиксов одного знака на предмет (совпадает с клампом конфига). */
    public static final int MAX_PER_SIGN = 3;

    /**
     * Сколько хороших аффиксов получает тир (индекс 0..6). Проклятому и слабому
     * бонусы не положены: их сила — в штрафах, а не в подарках.
     */
    private static final int[] GOOD_BY_TIER = {0, 0, 1, 1, 1, 2, 2};

    /**
     * Сколько плохих аффиксов получает тир. Дефекты живут только в нижней
     * трети лестницы: редкий и выше предмет с изъяном — уже не редкость.
     */
    private static final int[] BAD_BY_TIER = {2, 1, 1, 0, 0, 0, 0};

    private static final List<TierAffix> ALL = List.copyOf(build());
    private static final List<TierAffix> GOOD = filter(TierAffix.Polarity.GOOD);
    private static final List<TierAffix> BAD = filter(TierAffix.Polarity.BAD);
    private static final Map<String, TierAffix> BY_ID = indexById();

    private TierAffixes() {
    }

    /** Все аффиксы: сначала хорошие, потом плохие. */
    public static List<TierAffix> all() {
        return ALL;
    }

    /** Только хорошие аффиксы. */
    public static List<TierAffix> good() {
        return GOOD;
    }

    /** Только плохие аффиксы. */
    public static List<TierAffix> bad() {
        return BAD;
    }

    /** Пул одного знака. */
    public static List<TierAffix> byPolarity(TierAffix.Polarity polarity) {
        if (polarity == null) {
            return List.of();
        }
        return polarity.good() ? GOOD : BAD;
    }

    /** Аффикс по id (null — нет такого). */
    public static TierAffix byId(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Пул знака, отфильтрованный под вид предмета. */
    public static List<TierAffix> candidates(TierAffix.Polarity polarity, ItemKind kind) {
        List<TierAffix> result = new ArrayList<>();
        for (TierAffix affix : byPolarity(polarity)) {
            if (affix.fits(kind)) {
                result.add(affix);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Бросок {@code count} разных аффиксов одного знака. По возможности берутся
     * аффиксы с разными эффектами — два «+3% урона» подряд смотрятся как
     * ошибка, а не как вариативность. Если эффектов не хватает, добираем
     * оставшиеся.
     */
    public static List<TierAffix> roll(TierAffix.Polarity polarity, ItemKind kind, int count,
                                       Random random) {
        if (polarity == null || kind == null || count <= 0 || random == null) {
            return List.of();
        }
        List<TierAffix> pool = new ArrayList<>(candidates(polarity, kind));
        if (pool.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(pool, random);
        int take = Math.min(count, pool.size());
        List<TierAffix> picked = new ArrayList<>(take);
        Set<ForgeUpgrade.Effect> usedEffects = new HashSet<>();
        for (TierAffix affix : pool) {
            if (picked.size() >= take) {
                break;
            }
            if (usedEffects.add(affix.effect())) {
                picked.add(affix);
            }
        }
        for (TierAffix affix : pool) {
            if (picked.size() >= take) {
                break;
            }
            if (!picked.contains(affix)) {
                picked.add(affix);
            }
        }
        return List.copyOf(picked);
    }

    /** Сколько хороших аффиксов положено тиру, с учётом потолка конфига. */
    public static int goodCount(String tierId, int maxGood) {
        return countFor(tierId, GOOD_BY_TIER, maxGood);
    }

    /** Сколько плохих аффиксов положено тиру, с учётом потолка конфига. */
    public static int badCount(String tierId, int maxBad) {
        return countFor(tierId, BAD_BY_TIER, maxBad);
    }

    private static int countFor(String tierId, int[] table, int max) {
        int index = TierDef.indexOf(tierId);
        if (index < 0 || index >= table.length || max <= 0) {
            return 0;
        }
        return Math.min(Math.min(max, MAX_PER_SIGN), table[index]);
    }

    /**
     * Набор аффиксов конкретного предмета. Детерминирован: те же
     * {@code uid + тир + вид} всегда дают те же аффиксы, поэтому лор и эффекты
     * не «дрожат» между пересборками, а предпросмотр наковальни честен.
     */
    public static List<TierAffix> forItem(String uid, String tierId, ItemKind kind,
                                          int maxGood, int maxBad) {
        if (uid == null || uid.isBlank() || tierId == null || kind == null) {
            return List.of();
        }
        int goodCount = goodCount(tierId, maxGood);
        int badCount = badCount(tierId, maxBad);
        if (goodCount == 0 && badCount == 0) {
            return List.of();
        }
        Random random = new Random(seed(uid, tierId, kind));
        List<TierAffix> result = new ArrayList<>(goodCount + badCount);
        result.addAll(roll(TierAffix.Polarity.GOOD, kind, goodCount, random));
        result.addAll(roll(TierAffix.Polarity.BAD, kind, badCount, random));
        return List.copyOf(result);
    }

    /** Все прежние наборы при допустимых потолках: для точного удаления старых строк. */
    static List<TierAffix> possibleForItem(String uid, String tierId, ItemKind kind) {
        java.util.Set<TierAffix> result = new java.util.LinkedHashSet<>();
        for (int good = 0; good <= MAX_PER_SIGN; good++) {
            for (int bad = 0; bad <= MAX_PER_SIGN; bad++) {
                result.addAll(forItem(uid, tierId, kind, good, bad));
            }
        }
        return List.copyOf(result);
    }

    /** Детерминированный seed другого броска (зачарования) от того же предмета. */
    static long seed(String uid, String tierId, ItemKind kind, long salt) {
        return seed(uid, tierId, kind) * 31 + salt;
    }

    /** Детерминированный seed набора: uid предмета + тир + вид. */
    static long seed(String uid, String tierId, ItemKind kind) {
        long hash = 0x9E3779B97F4A7C15L;
        hash = hash * 31 + uid.hashCode();
        hash = hash * 31 + tierId.hashCode();
        hash = hash * 31 + kind.name().hashCode();
        // Перемешивание (splitmix64): соседние uid дают далёкие наборы.
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        return hash ^ (hash >>> 31);
    }

    /** Сколько аффиксов знака подходит виду предмета (диагностика и тесты). */
    public static int countFor(TierAffix.Polarity polarity, ItemKind kind) {
        return candidates(polarity, kind).size();
    }

    private static List<TierAffix> filter(TierAffix.Polarity polarity) {
        List<TierAffix> list = new ArrayList<>();
        for (TierAffix affix : ALL) {
            if (affix.polarity() == polarity) {
                list.add(affix);
            }
        }
        return List.copyOf(list);
    }

    private static Map<String, TierAffix> indexById() {
        Map<String, TierAffix> indexed = new LinkedHashMap<>();
        for (TierAffix affix : ALL) {
            indexed.put(affix.id(), affix);
        }
        return Map.copyOf(indexed);
    }

    /** Реестр: 15 хороших + 15 плохих, id уникальны. */
    private static List<TierAffix> build() {
        List<TierAffix> list = new ArrayList<>(GOOD_TOTAL + BAD_TOTAL);

        // ------------------------------------------------------------------ хорошие
        add(list, TierAffix.Polarity.GOOD, "fletching", "Оплётка",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.SNIPE, 3);
        add(list, TierAffix.Polarity.GOOD, "thirst", "Жажда",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.LIFESTEAL, 2);
        add(list, TierAffix.Polarity.GOOD, "hawk_eye", "Ястребиный глаз",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.CRIT_CHANCE, 2.5);
        add(list, TierAffix.Polarity.GOOD, "bone_crusher", "Костолом",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.ARMOR_PIERCE, 3);
        add(list, TierAffix.Polarity.GOOD, "hunter", "Промысловик",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.HUNTER_XP, 6);

        add(list, TierAffix.Polarity.GOOD, "iron_hide", "Железная шкура",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.ARMOR_PERCENT, 3);
        add(list, TierAffix.Polarity.GOOD, "warded_plate", "Оберег",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT, 2.5);
        add(list, TierAffix.Polarity.GOOD, "cat_landing", "Кошачья посадка",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.SAFE_FALL, 1.5);
        add(list, TierAffix.Polarity.GOOD, "second_wind", "Второе дыхание",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.REGEN_PULSE, 0.15);

        add(list, TierAffix.Polarity.GOOD, "night_eye", "Ночной глаз",
                ForgeUpgrade.Target.ANY, ForgeUpgrade.Effect.NIGHT_VISION, 1);
        add(list, TierAffix.Polarity.GOOD, "scavenger", "Мусорщик",
                ForgeUpgrade.Target.ANY, ForgeUpgrade.Effect.XP_GAIN, 5);

        add(list, TierAffix.Polarity.GOOD, "ore_hound", "Рудная гончая",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.ORE_DOUBLE, 5);
        add(list, TierAffix.Polarity.GOOD, "woodcutter", "Дровосек",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.LOG_DOUBLE, 6);
        add(list, TierAffix.Polarity.GOOD, "harvester", "Жнец",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.CROP_DOUBLE, 6);
        add(list, TierAffix.Polarity.GOOD, "fisher_luck", "Рыбацкая удача",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.FISH_DOUBLE, 6);

        // -------------------------------------------------------------------- плохие
        // Только атрибутные минусы: отрицательный шанс-эффект не срабатывал бы.
        add(list, TierAffix.Polarity.BAD, "chipped", "Скол",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.RAW_DAMAGE, -0.5);
        add(list, TierAffix.Polarity.BAD, "blunt", "Затупление",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.ATTACK_PERCENT, -4);
        add(list, TierAffix.Polarity.BAD, "heavy_bow", "Тяжёлая тетива",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.MOVE_SPEED_PERCENT, -4);
        add(list, TierAffix.Polarity.BAD, "short_haft", "Короткое древко",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.REACH, -0.2);
        add(list, TierAffix.Polarity.BAD, "slick_grip", "Скользкая рукоять",
                ForgeUpgrade.Target.WEAPON, ForgeUpgrade.Effect.ATTACK_KNOCKBACK, -0.1);

        add(list, TierAffix.Polarity.BAD, "loose_plates", "Разболтанные пластины",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.ARMOR_PERCENT, -5);
        add(list, TierAffix.Polarity.BAD, "thin_padding", "Жидкая подкладка",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.TOUGHNESS_FLAT, -0.4);
        add(list, TierAffix.Polarity.BAD, "dragging_feet", "Волочащиеся ноги",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.MOVE_SPEED_PERCENT, -5);
        add(list, TierAffix.Polarity.BAD, "glass_jaw", "Стеклянная челюсть",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.MAX_HEALTH, -1.0);
        add(list, TierAffix.Polarity.BAD, "stiff_joints", "Затёкшие суставы",
                ForgeUpgrade.Target.ARMOR, ForgeUpgrade.Effect.SNEAK_SPEED, -10);

        add(list, TierAffix.Polarity.BAD, "clumsy", "Неуклюжесть",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.DIG_SPEED_PERCENT, -8);
        add(list, TierAffix.Polarity.BAD, "dull_bit", "Тупой резец",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.MINING_EFFICIENCY, -1);
        add(list, TierAffix.Polarity.BAD, "short_arm", "Короткая рука",
                ForgeUpgrade.Target.TOOL, ForgeUpgrade.Effect.BLOCK_REACH, -0.2);

        add(list, TierAffix.Polarity.BAD, "bad_luck", "Дурной глаз",
                ForgeUpgrade.Target.ANY, ForgeUpgrade.Effect.LUCK, -1);
        add(list, TierAffix.Polarity.BAD, "frail", "Хлипкое сложение",
                ForgeUpgrade.Target.ANY, ForgeUpgrade.Effect.MAX_HEALTH, -0.5);

        return list;
    }

    private static void add(List<TierAffix> list, TierAffix.Polarity polarity, String id,
                            String name, ForgeUpgrade.Target target,
                            ForgeUpgrade.Effect effect, double value) {
        list.add(new TierAffix(id, name, polarity, target, effect, value));
    }
}
