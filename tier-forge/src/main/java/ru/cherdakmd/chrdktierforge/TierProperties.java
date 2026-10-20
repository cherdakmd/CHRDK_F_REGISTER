package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Реестр уникальных свойств тиров: по 15 на каждый из семи тиров (105 всего).
 *
 * <p>Правила выдачи:</p>
 * <ul>
 *   <li>свойство всегда принадлежит тиру предмета: проклятому не выпадет
 *       легендарное;</li>
 *   <li>свойство подбирается под вид предмета (оружие / броня / инструмент);
 *       «любое» подходит всем;</li>
 *   <li>в одном предмете не бывает двух одинаковых свойств;</li>
 *   <li>при перекате, слиянии и смене тира свойство пересбрасывается — новый
 *       тир приносит свой пул.</li>
 * </ul>
 *
 * <p>Класс чистый: ни Bukkit, ни конфига — пулы и броски проверяются
 * юнит-тестами, а применяет их {@link TierItems}.</p>
 */
public final class TierProperties {

    /** Сколько уникальных свойств у каждого тира. */
    public static final int PER_TIER = 15;
    /** Сколько свойств по умолчанию получает один предмет. */
    public static final int DEFAULT_PER_ITEM = 1;
    /** Больше трёх свойств на предмет — уже не «уникальное», а стена текста. */
    public static final int MAX_PER_ITEM = 3;

    private static final List<TierProperty> ALL = List.copyOf(build());
    private static final Map<String, List<TierProperty>> BY_TIER = groupByTier();
    private static final Map<String, TierProperty> BY_ID = indexById();

    private TierProperties() {
    }

    /** Все свойства в порядке реестра. */
    public static List<TierProperty> all() {
        return ALL;
    }

    /** Пул тира (пустой список — неизвестный тир). */
    public static List<TierProperty> byTier(String tier) {
        return BY_TIER.getOrDefault(tier == null ? "" : tier.trim().toLowerCase(Locale.ROOT),
                List.of());
    }

    /** Свойство по id (null — нет такого). */
    public static TierProperty byId(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** Пул тира, отфильтрованный под вид предмета. */
    public static List<TierProperty> candidates(String tier, ItemKind kind) {
        List<TierProperty> result = new ArrayList<>();
        for (TierProperty property : byTier(tier)) {
            if (property.fits(kind)) {
                result.add(property);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Бросок {@code count} разных свойств из пула тира под вид предмета.
     * Если подходящих меньше — вернётся сколько есть (никогда {@code null}).
     */
    public static List<TierProperty> roll(String tier, ItemKind kind, int count, Random random) {
        return roll(tier, kind, count, random, List.of());
    }

    /**
     * То же, но с исключением уже имеющихся свойств (повторный накат не должен
     * дублировать строку в лоре).
     */
    public static List<TierProperty> roll(String tier, ItemKind kind, int count, Random random,
                                          List<TierProperty> exclude) {
        if (count <= 0) {
            return List.of();
        }
        List<TierProperty> pool = new ArrayList<>(candidates(tier, kind));
        if (exclude != null) {
            for (TierProperty skip : exclude) {
                pool.remove(skip);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        int take = Math.min(count, pool.size());
        List<TierProperty> picked = new ArrayList<>(take);
        for (int index = 0; index < take; index++) {
            picked.add(pool.remove(random.nextInt(pool.size())));
        }
        return List.copyOf(picked);
    }

    /** Храним в PDC одной строкой: {@code rust,short_reach}. */
    public static String serialize(List<TierProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TierProperty property : properties) {
            if (property == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(property.id());
        }
        return builder.toString();
    }

    /** Разбор строки из PDC: неизвестные и повторные id пропускаются. */
    public static List<TierProperty> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<TierProperty> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            TierProperty property = byId(part);
            if (property != null) {
                result.add(property);
            }
        }
        return List.copyOf(result);
    }

    /** Сколько свойств тира подходит виду предмета (для диагностики и тестов). */
    public static int countFor(String tier, ItemKind kind) {
        return candidates(tier, kind).size();
    }

    private static Map<String, List<TierProperty>> groupByTier() {
        Map<String, List<TierProperty>> grouped = new LinkedHashMap<>();
        for (String tier : TierDef.LADDER) {
            List<TierProperty> pool = new ArrayList<>();
            for (TierProperty property : ALL) {
                if (property.tier().equals(tier)) {
                    pool.add(property);
                }
            }
            grouped.put(tier, List.copyOf(pool));
        }
        return Map.copyOf(grouped);
    }

    private static Map<String, TierProperty> indexById() {
        Map<String, TierProperty> indexed = new LinkedHashMap<>();
        for (TierProperty property : ALL) {
            indexed.put(property.id(), property);
        }
        return Map.copyOf(indexed);
    }

    /** Реестр: 7 тиров × 15 свойств, id уникальны. */
    private static List<TierProperty> build() {
        List<TierProperty> list = new ArrayList<>(7 * PER_TIER);

        // ---------------------------------------------------------------- проклятый
        // Только атрибутные минусы: отрицательный шанс-эффект просто не срабатывал бы.
        add(list, "rust", "cursed", "Ржавчина", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, -6);
        add(list, "leaden_grip", "cursed", "Свинцовый хват", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_SPEED_PERCENT, -10);
        add(list, "blunt_edge", "cursed", "Затупленное лезвие", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.RAW_DAMAGE, -1.5);
        add(list, "short_reach", "cursed", "Короткая рукоять", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.REACH, -0.3);
        add(list, "weak_swing", "cursed", "Слабый замах", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_KNOCKBACK, -0.2);

        add(list, "gap", "cursed", "Прореха", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, -8);
        add(list, "thin_leather", "cursed", "Тонкая кожа", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.TOUGHNESS_FLAT, -0.5);
        add(list, "dead_weight", "cursed", "Мёртвый груз", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.MOVE_SPEED_PERCENT, -8);
        add(list, "frailty", "cursed", "Немощь", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.MAX_HEALTH, -1.0);
        add(list, "rusty_joints", "cursed", "Ржавые сочленения", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.WALK_EFFICIENCY, -0.1);

        add(list, "dull_tooth", "cursed", "Затуплённый зуб", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, -8);
        add(list, "shaky_hands", "cursed", "Дрожащие руки", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.MINING_EFFICIENCY, -1);
        add(list, "short_grip", "cursed", "Короткий хват", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.BLOCK_REACH, -0.3);
        add(list, "midas_curse", "cursed", "Проклятие Мидаса", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.LUCK, -1);

        add(list, "heavy_burden", "cursed", "Тяжкая ноша", ForgeUpgrade.Target.ANY,
                ForgeUpgrade.Effect.MAX_HEALTH, -0.5);

        // ------------------------------------------------------------------- слабый
        add(list, "sharpened", "weak", "Подточено", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, 2);
        add(list, "quick_hand", "weak", "Быстрая рука", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_SPEED_PERCENT, 2);
        add(list, "first_blood", "weak", "Первый удар", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.FIRST_STRIKE, 2);
        add(list, "hunter_mark", "weak", "Метка охотника", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.HUNTER_XP, 5);
        add(list, "steady_aim", "weak", "Ровный прицел", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SNIPE, 3);

        add(list, "padded", "weak", "Подбивка", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 2);
        add(list, "light_step", "weak", "Лёгкий шаг", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.MOVE_SPEED_PERCENT, 1);
        add(list, "tough_skin", "weak", "Крепкая кожа", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.TOUGHNESS_FLAT, 0.2);
        add(list, "soft_landing", "weak", "Мягкое падение", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.SAFE_FALL, 0.5);
        add(list, "springy", "weak", "Пружинистость", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.JUMP, 3);

        add(list, "sharp_tooth", "weak", "Острый зуб", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.MINING_EFFICIENCY, 1);
        add(list, "quick_dig", "weak", "Сноровка", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 3);
        add(list, "ore_sense", "weak", "Чутьё на руду", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 3);
        add(list, "green_thumb", "weak", "Зелёный палец", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.CROP_DOUBLE, 4);
        add(list, "thrifty", "weak", "Бережливость", ForgeUpgrade.Target.ANY,
                ForgeUpgrade.Effect.DURABILITY_SAVE, 3);

        // ------------------------------------------------------------------ простой
        add(list, "keen_edge", "common", "Острая кромка", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.RAW_DAMAGE, 0.5);
        add(list, "holy_mark", "common", "Святая метка", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SMITE, 3);
        add(list, "finisher", "common", "Добивание", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.EXECUTE, 3);
        add(list, "spark", "common", "Искра", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.IGNITE, 8);
        add(list, "long_handle", "common", "Длинная рукоять", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.REACH, 0.15);

        add(list, "plating", "common", "Пластины", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 3);
        add(list, "thick_hide", "common", "Толстая шкура", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.TOUGHNESS_FLAT, 0.3);
        add(list, "deflect_guard", "common", "Отвод удара", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT, 2);
        add(list, "spikes", "common", "Шипы", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.THORNS, 3);
        add(list, "diver", "common", "Ныряльщик", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.OXYGEN, 0.1);

        add(list, "deep_swing", "common", "Глубокий взмах", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 4);
        add(list, "ore_eye", "common", "Глаз руды", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 4);
        add(list, "furnace_sense", "common", "Жар печи", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.SMELT_ORE, 5);
        add(list, "lumberjack", "common", "Валка", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.LOG_DOUBLE, 4);
        add(list, "lucky", "common", "Удача", ForgeUpgrade.Target.ANY,
                ForgeUpgrade.Effect.LUCK, 0.5);

        // ----------------------------------------------------------------- хороший
        add(list, "wing", "fine", "Крыло", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, 4);
        add(list, "crit_edge", "fine", "Грань крита", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.CRIT_CHANCE, 3);
        add(list, "drinker", "fine", "Кровопийца", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.LIFESTEAL, 1.5);
        add(list, "piercer", "fine", "Пронзатель", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ARMOR_PIERCE, 3);
        add(list, "frost_mark", "fine", "Морозная метка", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SLOW_HIT, 6);

        add(list, "fine_plate", "fine", "Тонкая ковка", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 4);
        add(list, "dense", "fine", "Плотность", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.TOUGHNESS_FLAT, 0.5);
        add(list, "sturdy", "fine", "Устойчивость", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.KB_RESIST, 0.1);
        add(list, "mending", "fine", "Затягивание", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.REGEN_PULSE, 0.15);
        add(list, "parry", "fine", "Отклонение", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.PARRY, 3);

        add(list, "miner_haste", "fine", "Забойный темп", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 6);
        add(list, "prospector", "fine", "Рудознатец", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 6);
        add(list, "column", "fine", "Колонный ход", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ZONE_BREAK, 1);
        add(list, "zeal", "fine", "Запал", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.PULSE_HASTE, 1);
        add(list, "gold_nose", "fine", "Золотое чутьё", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.GOLD_FIND, 4);

        // ------------------------------------------------------------------- редкий
        add(list, "razor", "rare", "Бритва", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, 6);
        add(list, "heavy_blade", "rare", "Тяжёлый клинок", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.RAW_DAMAGE, 1);
        add(list, "duelist", "rare", "Дуэлянт", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.PVP_DAMAGE, 5);
        add(list, "beast_slayer", "rare", "Зверобой", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ANIMAL_DAMAGE, 4);
        add(list, "frost_bite", "rare", "Ледяной укус", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.FREEZE_HIT, 6);
        add(list, "grapple", "rare", "Крюк", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.HOOK, 5);

        add(list, "rune_plate", "rare", "Рунная броня", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 5);
        add(list, "warded", "rare", "Оберег", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT, 4);
        add(list, "blast_ward", "rare", "Взрывозащита", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.EXPLOSION_DAMAGE_DOWN, 5);
        add(list, "spellward", "rare", "Антимагия", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.MAGIC_DAMAGE_DOWN, 5);
        add(list, "adrenal", "rare", "Адреналин", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ADRENALINE, 1);

        add(list, "burrow", "rare", "Проходчик", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 8);
        add(list, "vein", "rare", "Жила", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 8);
        add(list, "abyss", "rare", "Глубинник", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DEEP_DOUBLE, 6);
        add(list, "angler", "rare", "Рыбарь", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.FISH_DOUBLE, 6);

        // ----------------------------------------------------------------- эпический
        add(list, "soul_edge", "epic", "Клинок душ", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, 8);
        add(list, "crit_master", "epic", "Мастер крита", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.CRIT_CHANCE, 5);
        add(list, "vampire", "epic", "Вампир", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.LIFESTEAL, 3);
        add(list, "stormcaller", "epic", "Громовержец", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.THUNDER, 5);
        add(list, "withering", "epic", "Иссушение", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.WITHER_HEX, 6);
        add(list, "plunder", "epic", "Мародёр", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.LOOT_DOUBLE, 5);

        add(list, "aegis_plate", "epic", "Эгида", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 7);
        add(list, "stone_skin", "epic", "Каменная кожа", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT, 6);
        add(list, "absorb", "epic", "Поглощение", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ABSORB_PROC, 8);
        add(list, "resilience", "epic", "Стойкость", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.RESIST_PROC, 6);
        add(list, "mirror_ward", "epic", "Зеркальная защита", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.PROJECTILE_REFLECT, 6);

        add(list, "earth_breaker", "epic", "Земледробитель", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 12);
        add(list, "ore_lord", "epic", "Владыка руды", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 12);
        add(list, "tunnel", "epic", "Тоннель", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ZONE_BREAK, 2);
        add(list, "soul_echo", "epic", "Эхо души", ForgeUpgrade.Target.ANY,
                ForgeUpgrade.Effect.SOULBOUND, 1);

        // ----------------------------------------------------------------- легендарный
        add(list, "executioner", "legendary", "Клинок палача", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.ATTACK_PERCENT, 12);
        add(list, "colossus_edge", "legendary", "Исполинская грань", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.RAW_DAMAGE, 2);
        add(list, "fate_crit", "legendary", "Воля судьбы", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.CRIT_CHANCE, 7);
        add(list, "skywrath", "legendary", "Гнев небес", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.THUNDER, 8);
        add(list, "legend_sniper", "legendary", "Легендарный снайпер", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SNIPE, 10);
        add(list, "wind_sweep", "legendary", "Ветреный размах", ForgeUpgrade.Target.WEAPON,
                ForgeUpgrade.Effect.SWEEP_RATIO, 0.2);

        add(list, "sanctuary", "legendary", "Святилище", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.ARMOR_PERCENT, 10);
        add(list, "unbroken", "legendary", "Несокрушимость", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT, 8);
        add(list, "vitality", "legendary", "Жизненная сила", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.MAX_HEALTH, 2);
        add(list, "last_bastion", "legendary", "Последний бастион", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.LAST_STAND, 1);
        add(list, "everwarm", "legendary", "Вечное тепло", ForgeUpgrade.Target.ARMOR,
                ForgeUpgrade.Effect.NO_FREEZE, 1);

        add(list, "world_breaker", "legendary", "Сокрушитель мира", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.DIG_SPEED_PERCENT, 18);
        add(list, "motherlode", "legendary", "Материнская жила", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ORE_DOUBLE, 18);
        add(list, "quarry", "legendary", "Карьер", ForgeUpgrade.Target.TOOL,
                ForgeUpgrade.Effect.ZONE_BREAK, 3);
        add(list, "eternal", "legendary", "Вечность", ForgeUpgrade.Target.ANY,
                ForgeUpgrade.Effect.PULSE_MEND, 2);

        return List.copyOf(list);
    }

    private static void add(List<TierProperty> list, String id, String tier, String name,
                            ForgeUpgrade.Target target, ForgeUpgrade.Effect effect, double value) {
        list.add(new TierProperty(id, tier, name, target, effect, value));
    }
}
