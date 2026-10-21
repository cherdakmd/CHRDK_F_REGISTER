package ru.cherdakmd.chrdktreasures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.AQUA;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.BLAST;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.DASH;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.FLAME_NOVA;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.FROST_NOVA;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.HEAL;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.LEAP;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.SENSE;
import static ru.cherdakmd.chrdktreasures.TreasureAbility.Kind.SHIELD;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.BLINDNESS;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.GLOWING;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.HUNGER;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.LEVITATION;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.SLOWNESS;
import static ru.cherdakmd.chrdktreasures.TreasureCurse.Kind.WEAKNESS;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.ARMOR;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.ATTACK_DAMAGE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.DOUBLE_ORE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FALL_REDUCTION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FIRE_RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.FLAME;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.KNOCKBACK_RESISTANCE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.LIFESTEAL;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.LUCK;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MAX_HEALTH;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MINING_SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.MOVEMENT_SPEED;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.RANGED_DAMAGE;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.REGENERATION;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.WATER_BREATHING;
import static ru.cherdakmd.chrdktreasures.TreasureEffect.Kind.XP_BONUS;

/**
 * Каталог именных находок: 62 предмета.
 *
 * <p>14 регионов по 4 предмета (обычный, редкий, эпический, легендарный) и
 * 6 мифических, не привязанных к биому. Так у каждой локации есть своя
 * лестница: первая находка в пустыне — кирка, а легендарная — клинок фараона,
 * и это видно по названию, а не по случайному набору цифр.</p>
 *
 * <p>Числа подобраны так, чтобы легендарный предмет был лучше алмазного, но не
 * отменял незерит: максимум +22 % урона и +30 % скорости копания, ауры только
 * уровней I–II, а мифический пул — 6 предметов на весь сервер.</p>
 *
 * <p>У эпических, легендарных и мифических находок есть активная способность
 * ({@link TreasureAbility}) по правому клику, у легендарных и мифических —
 * ещё и проклятие-расплата ({@link TreasureCurse}). Обычные и редкие предметы
 * остаются пассивными: первые шаги не должны превращаться в набор кнопок.</p>
 */
public final class TreasureCatalog {

    public static final int EXPECTED_ITEMS = 62;
    public static final int PER_REGION = 4;
    public static final int MYTHICAL_ITEMS = 6;

    private static final List<TreasureItemDef> ALL = new ArrayList<>();
    private static final Map<String, TreasureItemDef> BY_ID = new HashMap<>();
    private static final Map<TreasureRegion, Map<TreasureTier, TreasureItemDef>> REGIONAL =
            new EnumMap<>(TreasureRegion.class);
    private static final List<TreasureItemDef> MYTHICAL = new ArrayList<>();

    static {
        region_plains();
        region_forest();
        region_taiga();
        region_snow();
        region_desert();
        region_savanna();
        region_jungle();
        region_swamp();
        region_mountain();
        region_ocean();
        region_deep();
        region_deep_dark();
        region_nether();
        region_end();
        mythic();
    }

    private TreasureCatalog() {
    }

    private static void region_plains() {
        add("plains-reap-hook", TreasureTier.COMMON, TreasureRegion.PLAINS, "IRON_HOE",
                effect(MINING_SPEED, 0.05), effect(XP_BONUS, 0.1));
        add("plains-herder-blade", TreasureTier.RARE, TreasureRegion.PLAINS, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.07));
        add("plains-sheaf-edge", TreasureTier.EPIC, TreasureRegion.PLAINS, "DIAMOND_SWORD",
                ability(HEAL, 3, 0, 60), null,
                effect(ATTACK_DAMAGE, 0.1), effect(LIFESTEAL, 0.03));
        add("plains-golden-scythe", TreasureTier.LEGENDARY, TreasureRegion.PLAINS, "NETHERITE_SWORD",
                ability(BLAST, 6, 5, 45), curse(HUNGER, 0.15),
                effect(ATTACK_DAMAGE, 0.16), effect(LIFESTEAL, 0.05), effect(FLAME, 0.1));
    }

    private static void region_forest() {
        add("forest-woodcutter", TreasureTier.COMMON, TreasureRegion.FOREST, "IRON_AXE",
                effect(MINING_SPEED, 0.06));
        add("forest-watch-helm", TreasureTier.RARE, TreasureRegion.FOREST, "CHAINMAIL_HELMET",
                effect(ARMOR, 1), effect(LUCK, 1));
        add("forest-lumber-axe", TreasureTier.EPIC, TreasureRegion.FOREST, "DIAMOND_AXE",
                ability(SENSE, 10, 12, 60), null,
                effect(MINING_SPEED, 0.12), effect(DOUBLE_ORE, 0.08));
        add("forest-root-guard", TreasureTier.LEGENDARY, TreasureRegion.FOREST, "NETHERITE_AXE",
                ability(BLAST, 6, 5, 45), curse(SLOWNESS, 0.12),
                effect(MINING_SPEED, 0.18), effect(DOUBLE_ORE, 0.15), effect(XP_BONUS, 0.25));
    }

    private static void region_taiga() {
        add("taiga-resin-pick", TreasureTier.COMMON, TreasureRegion.TAIGA, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.05));
        add("taiga-fur-boots", TreasureTier.RARE, TreasureRegion.TAIGA, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.03), effect(KNOCKBACK_RESISTANCE, 0.05));
        add("taiga-cloak", TreasureTier.EPIC, TreasureRegion.TAIGA, "DIAMOND_CHESTPLATE",
                ability(SHIELD, 10, 0, 60), null,
                effect(ARMOR, 2), effect(MAX_HEALTH, 2));
        add("taiga-winter-axe", TreasureTier.LEGENDARY, TreasureRegion.TAIGA, "NETHERITE_AXE",
                ability(FROST_NOVA, 6, 6, 40), curse(GLOWING, 0.08),
                effect(ATTACK_DAMAGE, 0.12), effect(FLAME, 0.08), effect(MINING_SPEED, 0.1));
    }

    private static void region_snow() {
        add("snow-snowshoes", TreasureTier.COMMON, TreasureRegion.SNOW, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.02), effect(FALL_REDUCTION, 0.1));
        add("snow-ice-pick", TreasureTier.RARE, TreasureRegion.SNOW, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.07));
        add("snow-blizzard-cloak", TreasureTier.EPIC, TreasureRegion.SNOW, "DIAMOND_CHESTPLATE",
                ability(FROST_NOVA, 5, 6, 60), null,
                effect(ARMOR, 3), effect(KNOCKBACK_RESISTANCE, 0.1));
        add("snow-frost-blade", TreasureTier.LEGENDARY, TreasureRegion.SNOW, "NETHERITE_SWORD",
                ability(FROST_NOVA, 7, 7, 40), curse(SLOWNESS, 0.15),
                effect(ATTACK_DAMAGE, 0.14), effect(LIFESTEAL, 0.04), effect(KNOCKBACK_RESISTANCE, 0.1));
    }

    private static void region_desert() {
        add("desert-sand-pick", TreasureTier.COMMON, TreasureRegion.DESERT, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.05));
        add("desert-nomad-bracers", TreasureTier.RARE, TreasureRegion.DESERT, "LEATHER_LEGGINGS",
                effect(ARMOR, 1), effect(MOVEMENT_SPEED, 0.02));
        add("desert-sun-blade", TreasureTier.EPIC, TreasureRegion.DESERT, "DIAMOND_SWORD",
                ability(FLAME_NOVA, 5, 5, 50), null,
                effect(ATTACK_DAMAGE, 0.09), effect(FLAME, 0.1));
        add("desert-pharaoh-blade", TreasureTier.LEGENDARY, TreasureRegion.DESERT, "NETHERITE_SWORD",
                ability(FLAME_NOVA, 8, 6, 35), curse(WEAKNESS, 0.15),
                effect(ATTACK_DAMAGE, 0.16), effect(FLAME, 0.15), effect(LUCK, 2));
    }

    private static void region_savanna() {
        add("savanna-hunter-spear", TreasureTier.COMMON, TreasureRegion.SAVANNA, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.04));
        add("savanna-runner-boots", TreasureTier.RARE, TreasureRegion.SAVANNA, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.05));
        add("savanna-garnet-cleaver", TreasureTier.EPIC, TreasureRegion.SAVANNA, "DIAMOND_AXE",
                ability(DASH, 1.2, 0, 30), null,
                effect(ATTACK_DAMAGE, 0.1), effect(LIFESTEAL, 0.03));
        add("savanna-fang", TreasureTier.LEGENDARY, TreasureRegion.SAVANNA, "NETHERITE_SWORD",
                ability(DASH, 1.4, 0, 25), curse(BLINDNESS, 0.08),
                effect(ATTACK_DAMAGE, 0.15), effect(LIFESTEAL, 0.06), effect(MOVEMENT_SPEED, 0.03));
    }

    private static void region_jungle() {
        add("jungle-machete", TreasureTier.COMMON, TreasureRegion.JUNGLE, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.04));
        add("jungle-vine-boots", TreasureTier.RARE, TreasureRegion.JUNGLE, "LEATHER_BOOTS",
                effect(MOVEMENT_SPEED, 0.04), effect(FALL_REDUCTION, 0.15));
        add("jungle-serpent-blade", TreasureTier.EPIC, TreasureRegion.JUNGLE, "DIAMOND_SWORD",
                ability(LEAP, 0.9, 0, 30), null,
                effect(ATTACK_DAMAGE, 0.11), effect(LIFESTEAL, 0.04));
        add("jungle-jaguar-fang", TreasureTier.LEGENDARY, TreasureRegion.JUNGLE, "NETHERITE_SWORD",
                ability(LEAP, 1.2, 0, 25), curse(GLOWING, 0.1),
                effect(ATTACK_DAMAGE, 0.17), effect(LIFESTEAL, 0.07), effect(LUCK, 2));
    }

    private static void region_swamp() {
        add("swamp-mire-helm", TreasureTier.COMMON, TreasureRegion.SWAMP, "CHAINMAIL_HELMET",
                effect(ARMOR, 1), effect(WATER_BREATHING, 1));
        add("swamp-bog-pick", TreasureTier.RARE, TreasureRegion.SWAMP, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.06), effect(XP_BONUS, 0.1));
        add("swamp-tin-shell", TreasureTier.EPIC, TreasureRegion.SWAMP, "DIAMOND_CHESTPLATE",
                ability(AQUA, 12, 0, 60), null,
                effect(ARMOR, 3), effect(MAX_HEALTH, 2));
        add("swamp-mire-blade", TreasureTier.LEGENDARY, TreasureRegion.SWAMP, "NETHERITE_SWORD",
                ability(AQUA, 20, 0, 45), curse(SLOWNESS, 0.12),
                effect(ATTACK_DAMAGE, 0.13), effect(LIFESTEAL, 0.05), effect(WATER_BREATHING, 1));
    }

    private static void region_mountain() {
        add("mountain-stonecutter", TreasureTier.COMMON, TreasureRegion.MOUNTAIN, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.05));
        add("mountain-climb-boots", TreasureTier.RARE, TreasureRegion.MOUNTAIN, "CHAINMAIL_BOOTS",
                effect(FALL_REDUCTION, 0.25), effect(MOVEMENT_SPEED, 0.03));
        add("mountain-prospector-pick", TreasureTier.EPIC, TreasureRegion.MOUNTAIN, "DIAMOND_PICKAXE",
                ability(LEAP, 0.8, 0, 40), null,
                effect(MINING_SPEED, 0.13), effect(DOUBLE_ORE, 0.1));
        add("mountain-peak-hammer", TreasureTier.LEGENDARY, TreasureRegion.MOUNTAIN, "NETHERITE_PICKAXE",
                ability(BLAST, 8, 6, 45), curse(HUNGER, 0.18),
                effect(MINING_SPEED, 0.2), effect(DOUBLE_ORE, 0.18), effect(XP_BONUS, 0.3));
    }

    private static void region_ocean() {
        add("ocean-shell-helm", TreasureTier.COMMON, TreasureRegion.OCEAN, "CHAINMAIL_HELMET",
                effect(ARMOR, 1), effect(WATER_BREATHING, 1));
        add("ocean-harpoon", TreasureTier.RARE, TreasureRegion.OCEAN, "TRIDENT",
                effect(ATTACK_DAMAGE, 0.08));
        add("ocean-coral-plate", TreasureTier.EPIC, TreasureRegion.OCEAN, "DIAMOND_CHESTPLATE",
                ability(AQUA, 15, 0, 55), null,
                effect(ARMOR, 3), effect(WATER_BREATHING, 1));
        add("ocean-spear", TreasureTier.LEGENDARY, TreasureRegion.OCEAN, "TRIDENT",
                ability(SHIELD, 15, 0, 40), curse(LEVITATION, 0.06),
                effect(ATTACK_DAMAGE, 0.16), effect(LIFESTEAL, 0.05), effect(WATER_BREATHING, 1));
    }

    private static void region_deep() {
        add("deep-miner-pick", TreasureTier.COMMON, TreasureRegion.DEEP, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.06));
        add("deep-lantern-helm", TreasureTier.RARE, TreasureRegion.DEEP, "IRON_HELMET",
                effect(ARMOR, 2), effect(LUCK, 1));
        add("deep-abyss-pick", TreasureTier.EPIC, TreasureRegion.DEEP, "DIAMOND_PICKAXE",
                ability(SENSE, 12, 14, 60), null,
                effect(MINING_SPEED, 0.14), effect(DOUBLE_ORE, 0.12));
        add("deep-core-drill", TreasureTier.LEGENDARY, TreasureRegion.DEEP, "NETHERITE_PICKAXE",
                ability(BLAST, 8, 6, 40), curse(HUNGER, 0.2),
                effect(MINING_SPEED, 0.22), effect(DOUBLE_ORE, 0.2), effect(XP_BONUS, 0.35));
    }

    private static void region_deep_dark() {
        add("deep-dark-sculk-leggings", TreasureTier.COMMON, TreasureRegion.DEEP_DARK, "CHAINMAIL_LEGGINGS",
                effect(ARMOR, 2));
        add("deep-dark-blade", TreasureTier.RARE, TreasureRegion.DEEP_DARK, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.08), effect(LIFESTEAL, 0.02));
        add("deep-dark-warden-plate", TreasureTier.EPIC, TreasureRegion.DEEP_DARK, "DIAMOND_CHESTPLATE",
                ability(SHIELD, 12, 0, 55), null,
                effect(ARMOR, 4), effect(KNOCKBACK_RESISTANCE, 0.15));
        add("deep-dark-echo-blade", TreasureTier.LEGENDARY, TreasureRegion.DEEP_DARK, "NETHERITE_SWORD",
                ability(SENSE, 14, 16, 45), curse(BLINDNESS, 0.1),
                effect(ATTACK_DAMAGE, 0.18), effect(LIFESTEAL, 0.06), effect(LUCK, 2));
    }

    private static void region_nether() {
        add("nether-fire-helm", TreasureTier.COMMON, TreasureRegion.NETHER, "GOLDEN_HELMET",
                effect(ARMOR, 1), effect(FIRE_RESISTANCE, 1));
        add("nether-basalt-cleaver", TreasureTier.RARE, TreasureRegion.NETHER, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.07), effect(FLAME, 0.1));
        add("nether-lava-blade", TreasureTier.EPIC, TreasureRegion.NETHER, "DIAMOND_SWORD",
                ability(FLAME_NOVA, 6, 6, 45), null,
                effect(ATTACK_DAMAGE, 0.12), effect(FLAME, 0.15));
        add("nether-flame-blade", TreasureTier.LEGENDARY, TreasureRegion.NETHER, "NETHERITE_SWORD",
                ability(FLAME_NOVA, 10, 7, 30), curse(HUNGER, 0.12),
                effect(ATTACK_DAMAGE, 0.17), effect(FLAME, 0.2), effect(FIRE_RESISTANCE, 1));
    }

    private static void region_end() {
        add("end-void-pick", TreasureTier.COMMON, TreasureRegion.END, "IRON_PICKAXE",
                effect(MINING_SPEED, 0.07));
        add("end-shard-sword", TreasureTier.RARE, TreasureRegion.END, "IRON_SWORD",
                effect(ATTACK_DAMAGE, 0.08), effect(LUCK, 1));
        add("end-chorus-plate", TreasureTier.EPIC, TreasureRegion.END, "DIAMOND_CHESTPLATE",
                ability(LEAP, 1, 0, 35), null,
                effect(ARMOR, 3), effect(MAX_HEALTH, 3));
        add("end-void-blade", TreasureTier.LEGENDARY, TreasureRegion.END, "NETHERITE_SWORD",
                ability(DASH, 1.5, 0, 25), curse(LEVITATION, 0.08),
                effect(ATTACK_DAMAGE, 0.18), effect(LIFESTEAL, 0.07));
    }

    private static void mythic() {
        add("mythic-heart-of-depths", TreasureTier.MYTHICAL, null, "NETHERITE_PICKAXE",
                ability(BLAST, 10, 8, 40), curse(HUNGER, 0.25),
                effect(MINING_SPEED, 0.3), effect(DOUBLE_ORE, 0.25), effect(XP_BONUS, 0.5));
        add("mythic-abyss-blade", TreasureTier.MYTHICAL, null, "NETHERITE_SWORD",
                ability(DASH, 1.8, 0, 20), curse(LEVITATION, 0.1),
                effect(ATTACK_DAMAGE, 0.22), effect(LIFESTEAL, 0.08));
        add("mythic-world-shell", TreasureTier.MYTHICAL, null, "NETHERITE_CHESTPLATE",
                ability(SHIELD, 20, 0, 45), curse(SLOWNESS, 0.2),
                effect(ARMOR, 4), effect(MAX_HEALTH, 4), effect(KNOCKBACK_RESISTANCE, 0.2));
        add("mythic-wanderer-boots", TreasureTier.MYTHICAL, null, "NETHERITE_BOOTS",
                ability(LEAP, 1.4, 0, 25), curse(WEAKNESS, 0.2),
                effect(MOVEMENT_SPEED, 0.08), effect(FALL_REDUCTION, 0.4));
        add("mythic-crown-of-abyss", TreasureTier.MYTHICAL, null, "NETHERITE_HELMET",
                ability(AQUA, 30, 0, 40), curse(BLINDNESS, 0.12),
                effect(REGENERATION, 1), effect(WATER_BREATHING, 1), effect(LUCK, 3));
        add("mythic-bow-of-providence", TreasureTier.MYTHICAL, null, "BOW",
                ability(SENSE, 15, 20, 45), curse(GLOWING, 0.15),
                effect(RANGED_DAMAGE, 0.25), effect(FLAME, 0.2));
    }

    private static TreasureEffect effect(TreasureEffect.Kind kind, double value) {
        return new TreasureEffect(kind, value);
    }

    private static TreasureAbility ability(TreasureAbility.Kind kind, double power,
                                           int radius, int cooldownSeconds) {
        return new TreasureAbility(kind, power, radius, cooldownSeconds);
    }

    private static TreasureCurse curse(TreasureCurse.Kind kind, double chance) {
        return new TreasureCurse(kind, chance);
    }

    private static void add(String id, TreasureTier tier, TreasureRegion region,
                            String material, TreasureEffect... effects) {
        add(id, tier, region, material, null, null, effects);
    }

    private static void add(String id, TreasureTier tier, TreasureRegion region,
                            String material, TreasureAbility ability, TreasureCurse curse,
                            TreasureEffect... effects) {
        TreasureItemDef def = new TreasureItemDef(id, tier, region, material,
                List.of(effects), 1, ability, curse);
        ALL.add(def);
        BY_ID.put(id, def);
        if (tier == TreasureTier.MYTHICAL) {
            MYTHICAL.add(def);
        } else {
            REGIONAL.computeIfAbsent(region, key -> new EnumMap<>(TreasureTier.class))
                    .put(tier, def);
        }
    }

    public static List<TreasureItemDef> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static int size() {
        return ALL.size();
    }

    public static TreasureItemDef find(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** Именная находка региона и редкости; {@code null} — нет такой. */
    public static TreasureItemDef forRegionTier(TreasureRegion region, TreasureTier tier) {
        if (region == null || tier == null) {
            return null;
        }
        if (tier == TreasureTier.MYTHICAL) {
            return null;
        }
        Map<TreasureTier, TreasureItemDef> row = REGIONAL.get(region);
        return row == null ? null : row.get(tier);
    }

    public static List<TreasureItemDef> mythical() {
        return Collections.unmodifiableList(MYTHICAL);
    }

    /** Все предметы редкости — для витрины коллекции. */
    public static List<TreasureItemDef> byTier(TreasureTier tier) {
        List<TreasureItemDef> out = new ArrayList<>();
        for (TreasureItemDef def : ALL) {
            if (def.tier() == tier) {
                out.add(def);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
