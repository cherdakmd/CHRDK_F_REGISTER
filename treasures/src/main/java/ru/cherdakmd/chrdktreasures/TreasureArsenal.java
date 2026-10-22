package ru.cherdakmd.chrdktreasures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.BLINK;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.BORE;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.EXPLOSION;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.FRENZY;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.FROST_NOVA;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.FROST_STRIKE;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.GUST;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.HEAL;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.HORN;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.NECROMANCY;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.SENSE;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.SHIELD;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.SPIKES;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.TIDE;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.VENOM;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.WITHER_STRIKE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.ARMOR;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.ATTACK_DAMAGE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.DOUBLE_ORE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FALL_REDUCTION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FIRE_RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FLAME;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.JUMP_BOOST;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.KNOCKBACK_RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.LIFESTEAL;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.LUCK;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MAX_HEALTH;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MINING_SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MOVEMENT_SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.RANGED_DAMAGE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.REGENERATION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.WATER_BREATHING;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.XP_BONUS;

/**
 * Арсенал Мирового Клада: предметы, перенесённые из датапака Mine Treasure 1.6.5.
 *
 * <p>Датапак остаётся источником вдохновения и набора механик, но не копируется:
 * его JSON-функции и loot-таблицы завязаны на скоры и хранилища данных, поэтому
 * механики переписаны нативно на Bukkit. Названия, описания и подсказки —
 * русские; иды латиницей, чтобы не ломать PDC и сообщения.</p>
 *
 * <p>Каталог живёт рядом с {@link TreasureCatalog}: классические 124 находки
 * остаются «лестницей биома» (по две на редкость), а арсенал из 72 предметов —
 * параллельный пул тех же регионов и редкостей. При генерации клада плагин сначала решает,
 * из какого пула брать предмет ({@code arsenal.chance}), поэтому датапаковые
 * вещи не вытесняют классические, а дополняют их.</p>
 *
 * <p>Способности арсенала подчиняются тем же ограничениям: не разрушают блоки,
 * не поджигают мир, не бьют игроков и не работают в чужих приватах — это
 * свойство самой {@link TreasureAbility}, а не отдельных предметов.</p>
 */
public final class TreasureArsenal {

    /** Сколько предметов перенесено из датапака. */
    public static final int EXPECTED_SIZE = 72;

    private static final List<TreasureItemDef> ALL = new ArrayList<>();
    private static final Map<String, TreasureItemDef> BY_ID = new HashMap<>();
    private static final Map<TreasureRegion, Map<TreasureTier, List<TreasureItemDef>>> BY_REGION =
            new EnumMap<>(TreasureRegion.class);
    private static final List<TreasureItemDef> MYTHICAL = new ArrayList<>();

    static {
        weapons();
        armor();
        utility();
        if (ALL.size() != EXPECTED_SIZE) {
            throw new IllegalStateException("арсенал: ожидалось " + EXPECTED_SIZE
                    + " предметов, а зарегистрировано " + ALL.size());
        }
    }

    private TreasureArsenal() {
    }

    /* ------------------------------- регистрация ------------------------------ */

    private static void weapons() {
        add("dp-assassin-blade", TreasureTier.LEGENDARY, TreasureRegion.DESERT, "GOLDEN_SWORD",
                ability(FRENZY, 6, 0, 45),
                effect(ATTACK_DAMAGE, 0.12), effect(LIFESTEAL, 0.04));
        add("dp-barbaric-axe", TreasureTier.LEGENDARY, TreasureRegion.FOREST, "GOLDEN_AXE",
                ability(EXPLOSION, 6, 4, 40),
                effect(ATTACK_DAMAGE, 0.14), effect(KNOCKBACK_RESISTANCE, 0.1));
        add("dp-ice-sword", TreasureTier.EPIC, TreasureRegion.SNOW, "DIAMOND_SWORD",
                ability(FROST_STRIKE, 5, 5, 30),
                effect(ATTACK_DAMAGE, 0.08), effect(KNOCKBACK_RESISTANCE, 0.05));
        add("dp-stray-bow", TreasureTier.LEGENDARY, TreasureRegion.SNOW, "BOW",
                ability(FROST_NOVA, 6, 6, 45),
                effect(RANGED_DAMAGE, 0.22), effect(LUCK, 1));
        add("dp-rumble-axe", TreasureTier.LEGENDARY, TreasureRegion.JUNGLE, "WOODEN_AXE",
                ability(FRENZY, 8, 0, 60),
                effect(ATTACK_DAMAGE, 0.1), effect(MOVEMENT_SPEED, 0.02));
        add("dp-wither-sword", TreasureTier.EPIC, TreasureRegion.NETHER, "STONE_SWORD",
                ability(WITHER_STRIKE, 6, 5, 35),
                effect(ATTACK_DAMAGE, 0.08), effect(LIFESTEAL, 0.02));
        add("dp-oceanic-spear", TreasureTier.LEGENDARY, TreasureRegion.OCEAN, "TRIDENT",
                ability(TIDE, 8, 8, 45),
                effect(ATTACK_DAMAGE, 0.14), effect(WATER_BREATHING, 1));
        add("dp-toxic-machete", TreasureTier.EPIC, TreasureRegion.SWAMP, "WOODEN_SWORD",
                ability(VENOM, 6, 5, 40),
                effect(ATTACK_DAMAGE, 0.08), effect(LIFESTEAL, 0.02));
        add("dp-warped-bow", TreasureTier.LEGENDARY, TreasureRegion.NETHER, "BOW",
                ability(BLINK, 12, 0, 40),
                effect(RANGED_DAMAGE, 0.18), effect(MOVEMENT_SPEED, 0.02));
        add("dp-hurricane-sword", TreasureTier.EPIC, TreasureRegion.MOUNTAIN, "IRON_SWORD",
                ability(GUST, 6, 6, 35),
                effect(ATTACK_DAMAGE, 0.1), effect(KNOCKBACK_RESISTANCE, 0.05));
        add("dp-giga-bore", TreasureTier.MYTHICAL, null, "NETHERITE_PICKAXE",
                ability(BORE, 25, 0, 60),
                effect(MINING_SPEED, 0.3), effect(DOUBLE_ORE, 0.25));
        // ---- 1.4.141: вторая волна арсенала ----
        add("dp-frostbite-axe", TreasureTier.LEGENDARY, TreasureRegion.SNOW, "DIAMOND_AXE",
                ability(FROST_STRIKE,6,4,40),
                effect(ATTACK_DAMAGE, 0.13), effect(MINING_SPEED, 0.08));
        add("dp-sandstorm-sword", TreasureTier.EPIC, TreasureRegion.DESERT, "IRON_SWORD",
                ability(GUST,5,5,35),
                effect(ATTACK_DAMAGE, 0.09));
        add("dp-grove-spear", TreasureTier.EPIC, TreasureRegion.FOREST, "TRIDENT",
                ability(HEAL,5,0,50),
                effect(ATTACK_DAMAGE, 0.09));
        add("dp-peak-bow", TreasureTier.LEGENDARY, TreasureRegion.MOUNTAIN, "BOW",
                ability(GUST,7,6,45),
                effect(RANGED_DAMAGE, 0.2), effect(LUCK, 1));
        add("dp-marsh-bow", TreasureTier.EPIC, TreasureRegion.SWAMP, "BOW",
                ability(VENOM,6,5,40),
                effect(RANGED_DAMAGE, 0.15));
        add("dp-void-scythe", TreasureTier.LEGENDARY, TreasureRegion.END, "DIAMOND_SWORD",
                ability(BLINK,14,0,35),
                effect(ATTACK_DAMAGE, 0.13), effect(LIFESTEAL, 0.03));
        add("dp-lava-axe", TreasureTier.EPIC, TreasureRegion.NETHER, "GOLDEN_AXE",
                ability(EXPLOSION,5,4,45),
                effect(ATTACK_DAMAGE, 0.1), effect(FLAME, 0.1));
        add("dp-coral-trident", TreasureTier.EPIC, TreasureRegion.OCEAN, "TRIDENT",
                ability(TIDE,7,7,40),
                effect(ATTACK_DAMAGE, 0.1), effect(WATER_BREATHING, 1));
        add("dp-plains-pitchfork", TreasureTier.LEGENDARY, TreasureRegion.PLAINS, "IRON_SWORD",
                ability(FRENZY,6,0,45),
                effect(ATTACK_DAMAGE, 0.1));
        add("dp-jungle-razor", TreasureTier.EPIC, TreasureRegion.JUNGLE, "WOODEN_SWORD",
                ability(FRENZY,5,0,40),
                effect(ATTACK_DAMAGE, 0.09));
        add("dp-dawn-blade", TreasureTier.MYTHICAL, null, "NETHERITE_SWORD",
                ability(FRENZY,10,0,35),
                effect(ATTACK_DAMAGE, 0.2), effect(LIFESTEAL, 0.06));
    }

    private static void armor() {
        add("dp-badlands-helmet", TreasureTier.RARE, TreasureRegion.DESERT, "LEATHER_HELMET",
                effect(RANGED_DAMAGE, 0.1), effect(LUCK, 1));
        add("dp-desert-chestplate", TreasureTier.EPIC, TreasureRegion.DESERT, "LEATHER_CHESTPLATE",
                effect(ATTACK_DAMAGE, 0.1), effect(MAX_HEALTH, 2));
        add("dp-dark-forest-chestplate", TreasureTier.EPIC, TreasureRegion.FOREST, "LEATHER_CHESTPLATE",
                effect(ATTACK_DAMAGE, 0.1), effect(FLAME, 0.1));
        add("dp-mushroom-shield", TreasureTier.EPIC, TreasureRegion.FOREST, "SHIELD",
                effect(ARMOR, 2), effect(REGENERATION, 1));
        add("dp-flower-helmet", TreasureTier.EPIC, TreasureRegion.PLAINS, "LEATHER_HELMET",
                effect(REGENERATION, 1), effect(LUCK, 2));
        add("dp-ice-shoes", TreasureTier.RARE, TreasureRegion.SNOW, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.04), effect(KNOCKBACK_RESISTANCE, 0.1));
        add("dp-jungle-shoes", TreasureTier.RARE, TreasureRegion.JUNGLE, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.04), effect(JUMP_BOOST, 1));
        add("dp-swamp-chestplate", TreasureTier.EPIC, TreasureRegion.SWAMP, "LEATHER_CHESTPLATE",
                effect(ARMOR, 3), effect(RESISTANCE, 1));
        add("dp-mountain-leggings", TreasureTier.RARE, TreasureRegion.MOUNTAIN, "LEATHER_LEGGINGS",
                effect(RESISTANCE, 1), effect(FIRE_RESISTANCE, 1));
        add("dp-wind-chestplate", TreasureTier.EPIC, TreasureRegion.MOUNTAIN, "LEATHER_CHESTPLATE",
                effect(KNOCKBACK_RESISTANCE, 0.2), effect(MOVEMENT_SPEED, 0.04));
        add("dp-ocean-chestplate", TreasureTier.EPIC, TreasureRegion.OCEAN, "LEATHER_CHESTPLATE",
                effect(WATER_BREATHING, 1), effect(ARMOR, 2));
        add("dp-dripstone-helmet", TreasureTier.RARE, TreasureRegion.DEEP, "LEATHER_HELMET",
                effect(MINING_SPEED, 0.08), effect(ARMOR, 1));
        add("dp-lush-pants", TreasureTier.EPIC, TreasureRegion.DEEP, "LEATHER_LEGGINGS",
                effect(ARMOR, 2), effect(REGENERATION, 1));
        add("dp-basalt-boots", TreasureTier.RARE, TreasureRegion.NETHER, "LEATHER_BOOTS",
                effect(KNOCKBACK_RESISTANCE, 0.15), effect(ARMOR, 1));
        add("dp-crimson-chestplate", TreasureTier.EPIC, TreasureRegion.NETHER, "LEATHER_CHESTPLATE",
                effect(ATTACK_DAMAGE, 0.1), effect(LIFESTEAL, 0.03));
        add("dp-warped-helmet", TreasureTier.EPIC, TreasureRegion.NETHER, "LEATHER_HELMET",
                effect(SPEED, 1), effect(ARMOR, 2));
        add("dp-soul-helmet", TreasureTier.LEGENDARY, TreasureRegion.NETHER, "LEATHER_HELMET",
                effect(RESISTANCE, 1), effect(MAX_HEALTH, 2));
        add("dp-end-boots", TreasureTier.RARE, TreasureRegion.END, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.05), effect(FALL_REDUCTION, 0.25));
        add("dp-savanna-helmet", TreasureTier.RARE, TreasureRegion.SAVANNA, "LEATHER_HELMET",
                effect(LUCK, 2), effect(XP_BONUS, 0.15));
        add("dp-taiga-pants", TreasureTier.RARE, TreasureRegion.TAIGA, "LEATHER_LEGGINGS",
                effect(REGENERATION, 1), effect(MOVEMENT_SPEED, 0.03));
        // ---- 1.4.141: вторая волна арсенала ----
        add("dp-taiga-coat", TreasureTier.EPIC, TreasureRegion.TAIGA, "LEATHER_CHESTPLATE",
                effect(ARMOR, 3), effect(RESISTANCE, 1));
        add("dp-savanna-sandals", TreasureTier.RARE, TreasureRegion.SAVANNA, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.05));
        add("dp-desert-veil", TreasureTier.EPIC, TreasureRegion.DESERT, "LEATHER_HELMET",
                effect(ARMOR, 2), effect(FIRE_RESISTANCE, 1));
        add("dp-forest-boots", TreasureTier.RARE, TreasureRegion.FOREST, "LEATHER_BOOTS",
                effect(FALL_REDUCTION, 0.25));
        add("dp-snow-parka", TreasureTier.LEGENDARY, TreasureRegion.SNOW, "LEATHER_CHESTPLATE",
                effect(ARMOR, 3), effect(MAX_HEALTH, 2));
        add("dp-plains-apron", TreasureTier.RARE, TreasureRegion.PLAINS, "LEATHER_CHESTPLATE",
                effect(ARMOR, 2));
        add("dp-jungle-helm", TreasureTier.RARE, TreasureRegion.JUNGLE, "LEATHER_HELMET",
                effect(MOVEMENT_SPEED, 0.03));
        add("dp-swamp-boots", TreasureTier.RARE, TreasureRegion.SWAMP, "LEATHER_BOOTS",
                effect(WATER_BREATHING, 1));
        add("dp-mountain-helm", TreasureTier.EPIC, TreasureRegion.MOUNTAIN, "LEATHER_HELMET",
                effect(KNOCKBACK_RESISTANCE, 0.15));
        add("dp-ocean-leggings", TreasureTier.RARE, TreasureRegion.OCEAN, "LEATHER_LEGGINGS",
                effect(ARMOR, 2));
        add("dp-deep-vest", TreasureTier.RARE, TreasureRegion.DEEP, "LEATHER_CHESTPLATE",
                effect(LUCK, 1), effect(XP_BONUS, 0.1));
        add("dp-deep-dark-boots", TreasureTier.EPIC, TreasureRegion.DEEP_DARK, "LEATHER_BOOTS",
                effect(ARMOR, 2), effect(MOVEMENT_SPEED, 0.03));
        add("dp-nether-boots", TreasureTier.EPIC, TreasureRegion.NETHER, "LEATHER_BOOTS",
                effect(FIRE_RESISTANCE, 1), effect(SPEED, 1));
        add("dp-end-helm", TreasureTier.EPIC, TreasureRegion.END, "LEATHER_HELMET",
                effect(ARMOR, 2), effect(LUCK, 2));
        add("dp-taiga-boots", TreasureTier.RARE, TreasureRegion.TAIGA, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.04));
        add("dp-savanna-chest", TreasureTier.EPIC, TreasureRegion.SAVANNA, "LEATHER_CHESTPLATE",
                effect(ARMOR, 2), effect(MOVEMENT_SPEED, 0.03));
        add("dp-desert-leggings", TreasureTier.RARE, TreasureRegion.DESERT, "LEATHER_LEGGINGS",
                effect(ARMOR, 2));
        add("dp-forest-helm", TreasureTier.EPIC, TreasureRegion.FOREST, "LEATHER_HELMET",
                effect(REGENERATION, 1), effect(ARMOR, 1));
        add("dp-snow-leggings", TreasureTier.EPIC, TreasureRegion.SNOW, "LEATHER_LEGGINGS",
                effect(ARMOR, 2), effect(FALL_REDUCTION, 0.2));
        add("dp-plains-boots", TreasureTier.RARE, TreasureRegion.PLAINS, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.04));
    }

    private static void utility() {
        add("dp-healing-flower", TreasureTier.EPIC, TreasureRegion.PLAINS, "SUNFLOWER",
                ability(HEAL, 8, 0, 45),
                effect(REGENERATION, 1), effect(LUCK, 1));
        add("dp-mountain-horn", TreasureTier.LEGENDARY, TreasureRegion.MOUNTAIN, "GOAT_HORN",
                ability(HORN, 10, 10, 90),
                effect(MOVEMENT_SPEED, 0.03), effect(RESISTANCE, 1));
        add("dp-sense-urgency", TreasureTier.EPIC, TreasureRegion.DEEP_DARK, "CLOCK",
                ability(SENSE, 15, 20, 60),
                effect(MINING_SPEED, 0.08), effect(XP_BONUS, 0.2));
        add("dp-floor-spikes", TreasureTier.EPIC, TreasureRegion.SWAMP, "POINTED_DRIPSTONE",
                ability(SPIKES, 10, 5, 60),
                effect(ARMOR, 1), effect(KNOCKBACK_RESISTANCE, 0.1));
        add("dp-necromancy", TreasureTier.LEGENDARY, TreasureRegion.NETHER, "WITHER_SKELETON_SKULL",
                ability(NECROMANCY, 20, 0, 120),
                effect(ATTACK_DAMAGE, 0.08), effect(LIFESTEAL, 0.04));
        // ---- 1.4.141: вторая волна арсенала ----
        add("dp-field-kit", TreasureTier.EPIC, TreasureRegion.PLAINS, "SHIELD",
                effect(ARMOR, 2), effect(REGENERATION, 1));
        add("dp-depth-charm", TreasureTier.EPIC, TreasureRegion.DEEP_DARK, "ECHO_SHARD",
                ability(SENSE,16,20,60),
                effect(MINING_SPEED, 0.1));
        add("dp-storm-horn", TreasureTier.LEGENDARY, TreasureRegion.MOUNTAIN, "GOAT_HORN",
                ability(HORN,12,12,90),
                effect(MOVEMENT_SPEED, 0.04));
        add("dp-healing-spring", TreasureTier.EPIC, TreasureRegion.OCEAN, "HEART_OF_THE_SEA",
                effect(REGENERATION, 2), effect(LUCK, 1));
        add("dp-soul-lantern", TreasureTier.EPIC, TreasureRegion.NETHER, "SOUL_LANTERN",
                ability(SHIELD,10,0,60),
                effect(REGENERATION, 1));
    }

    /* --------------------------------- запросы -------------------------------- */

    public static List<TreasureItemDef> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static int size() {
        return ALL.size();
    }

    public static TreasureItemDef find(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static List<TreasureItemDef> mythical() {
        return Collections.unmodifiableList(MYTHICAL);
    }

    /** Пул арсенала для региона и редкости; пустой список — нет предметов. */
    public static List<TreasureItemDef> pool(TreasureRegion region, TreasureTier tier) {
        if (tier == null) {
            return List.of();
        }
        if (tier == TreasureTier.MYTHICAL) {
            return MYTHICAL;
        }
        if (region == null) {
            return List.of();
        }
        Map<TreasureTier, List<TreasureItemDef>> row = BY_REGION.get(region);
        if (row == null) {
            return List.of();
        }
        return row.getOrDefault(tier, List.of());
    }

    /** Сколько предметов арсенала относится к региону (для витрины). */
    public static int countInRegion(TreasureRegion region) {
        int total = 0;
        for (TreasureTier tier : TreasureTier.values()) {
            if (tier.regional()) {
                total += pool(region, tier).size();
            }
        }
        return total;
    }

    /* ------------------------------- регистрация ------------------------------ */

    private static TreasureEffect effect(TreasureEffect.Kind kind, double value) {
        return new TreasureEffect(kind, value);
    }

    private static TreasureAbility ability(TreasureAbility.Kind kind, double power,
                                           int radius, int cooldownSeconds) {
        return new TreasureAbility(kind, power, radius, cooldownSeconds);
    }

    private static void add(String id, TreasureTier tier, TreasureRegion region, String material,
                            TreasureEffect... effects) {
        add(id, tier, region, material, null, effects);
    }

    private static void add(String id, TreasureTier tier, TreasureRegion region, String material,
                            TreasureAbility ability, TreasureEffect... effects) {
        if (BY_ID.containsKey(id)) {
            throw new IllegalStateException("арсенал: повторяющийся ид " + id);
        }
        TreasureItemDef def = new TreasureItemDef(id, tier, region, material,
                List.of(effects), 1, ability, null);
        ALL.add(def);
        BY_ID.put(id, def);
        if (tier == TreasureTier.MYTHICAL) {
            MYTHICAL.add(def);
        } else {
            BY_REGION.computeIfAbsent(region, key -> new EnumMap<>(TreasureTier.class))
                    .computeIfAbsent(tier, key -> new ArrayList<>())
                    .add(def);
        }
    }
}
