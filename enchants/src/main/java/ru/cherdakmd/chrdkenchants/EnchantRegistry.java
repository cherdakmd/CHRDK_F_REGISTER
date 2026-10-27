package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.cherdakmd.chrdkenchants.EnchantDef.Polarity.CURSE;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Polarity.LIGHT;
import static ru.cherdakmd.chrdkenchants.ItemGroup.AXE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.BOOTS;
import static ru.cherdakmd.chrdkenchants.ItemGroup.BOW;
import static ru.cherdakmd.chrdkenchants.ItemGroup.CHESTPLATE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.CROSSBOW;
import static ru.cherdakmd.chrdkenchants.ItemGroup.HELMET;
import static ru.cherdakmd.chrdkenchants.ItemGroup.HOE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.LEGGINGS;
import static ru.cherdakmd.chrdkenchants.ItemGroup.PICKAXE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.SHOVEL;
import static ru.cherdakmd.chrdkenchants.ItemGroup.SWORD;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.COMMON;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.EPIC;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.LEGENDARY;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.RARE;

/**
 * Реестр всех кастомных чар (1.4.204: 70 — 55 светлых и 15 проклятий).
 *
 * <p>Первые 30 — «эпические» чары из 1.4.13 (Громобой, Вампиризм, Феникс…).
 * Волна 18 добавила 15 новых светлых чар (статы: урон, скорость стрел, защита,
 * регенерация, опыт) и 15 проклятий: они выпадают со стола с шансом и вешают
 * на предмет настоящий минус — от «Тупого лезвия» до «Проклятой крови».</p>
 */
public final class EnchantRegistry {

    /** Все группы предметов — для чар, которые ложатся на что угодно. */
    private static final Set<ItemGroup> ANY = Collections.unmodifiableSet(EnumSet.allOf(ItemGroup.class));

    private static final Map<String, EnchantDef> REGISTRY = new LinkedHashMap<>();

    static {
        // ------------------------- 30 эпических чар (1.4.13–1.4.124) -------------------------
        register("thunderstrike", RARE, 3, "lightning", LIGHT, SWORD, AXE);
        register("lifesteal", EPIC, 3, null, LIGHT, SWORD, AXE);
        register("chainspark", EPIC, 2, "lightning", LIGHT, SWORD, AXE);
        register("bleeding", RARE, 3, null, LIGHT, SWORD, AXE);
        register("paralyze", EPIC, 2, null, LIGHT, SWORD, AXE);
        register("execution", LEGENDARY, 2, null, LIGHT, SWORD, AXE);
        register("berserk", RARE, 3, null, LIGHT, SWORD, AXE);
        register("slayer", RARE, 2, null, LIGHT, SWORD, AXE);
        register("souleater", EPIC, 2, null, LIGHT, SWORD, AXE);
        register("stormarrow", RARE, 3, "impact-aoe", LIGHT, BOW, CROSSBOW);
        register("explosive", EPIC, 2, "impact-aoe", LIGHT, BOW, CROSSBOW);
        register("sniper", RARE, 3, null, LIGHT, BOW, CROSSBOW);
        register("frostarrow", RARE, 2, null, LIGHT, BOW, CROSSBOW);
        register("tracer", COMMON, 1, null, LIGHT, BOW, CROSSBOW);
        register("headhunter", LEGENDARY, 1, null, LIGHT, BOW, CROSSBOW);
        register("retribution", EPIC, 3, "aura", LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("laststand", LEGENDARY, 1, "savior", LIGHT, CHESTPLATE, LEGGINGS);
        register("fortified", RARE, 3, null, LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("lightningrod", COMMON, 1, null, LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("phoenix", LEGENDARY, 1, "savior", LIGHT, CHESTPLATE);
        register("iceward", RARE, 2, "aura", LIGHT, LEGGINGS);
        register("truesight", EPIC, 1, null, LIGHT, HELMET);
        register("seer", COMMON, 1, null, LIGHT, HELMET);
        register("swiftfeet", RARE, 2, null, LIGHT, BOOTS);
        register("froggy", COMMON, 2, null, LIGHT, BOOTS);
        register("lavawalker", LEGENDARY, 1, null, LIGHT, BOOTS);
        register("autosmelt", RARE, 1, null, LIGHT, PICKAXE);
        register("veinminer", EPIC, 3, "megamine", LIGHT, PICKAXE);
        register("excavator", EPIC, 2, "megamine", LIGHT, PICKAXE, SHOVEL, AXE);
        register("midas", LEGENDARY, 1, null, LIGHT, PICKAXE);

        // ------------------------- 15 новых светлых чар (1.4.128) -------------------------
        register("keen_edge", RARE, 3, null, LIGHT, SWORD, AXE);
        register("frenzy", RARE, 3, null, LIGHT, SWORD, AXE);
        register("plunder", COMMON, 3, null, LIGHT, SWORD, AXE);
        register("duelist", EPIC, 3, null, LIGHT, SWORD, AXE);
        register("steady_aim", RARE, 3, null, LIGHT, BOW, CROSSBOW);
        register("quickdraw", COMMON, 3, null, LIGHT, BOW, CROSSBOW);
        register("bulwark", RARE, 3, "ward", LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("second_skin", EPIC, 3, "ward", LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("vitality", RARE, 3, null, LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("grounded", COMMON, 3, null, LIGHT, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("lightfoot", RARE, 3, null, LIGHT, BOOTS);
        register("featherstep", COMMON, 3, null, LIGHT, BOOTS);
        register("prospector", RARE, 3, null, LIGHT, PICKAXE, SHOVEL, HOE, AXE);
        register("fortunate", EPIC, 2, null, LIGHT, PICKAXE);
        register("scholar", COMMON, 3, null, LIGHT, ANY);

        // PvE-контроль: I уровень эффекта, общая перезарядка, без урона игрокам/питомцам.
        register("crippling_edge", RARE, 3, "pve-melee-control", LIGHT, SWORD, AXE);
        register("sapping_edge", EPIC, 2, "pve-melee-control", LIGHT, SWORD, AXE);
        register("pinning_shot", RARE, 3, "pve-ranged-control", LIGHT, BOW, CROSSBOW);
        register("sapping_shot", EPIC, 2, "pve-ranged-control", LIGHT, BOW, CROSSBOW);
        register("briar_guard", RARE, 2, "pve-guard-control", LIGHT, CHESTPLATE, LEGGINGS);
        register("faltering_guard", RARE, 2, "pve-guard-control", LIGHT, CHESTPLATE, LEGGINGS);

        // Утилиты 1.4.204: никакой дополнительной добычи или опыта.
        register("careful_work", RARE, 3, null, LIGHT, PICKAXE, SHOVEL, HOE, AXE);
        register("garden_step", COMMON, 1, null, LIGHT, BOOTS);
        register("air_reserve", RARE, 2, null, LIGHT, HELMET);
        register("quiet_step", EPIC, 1, null, LIGHT, BOOTS);

        // --------------------------- 15 проклятий (1.4.128) ---------------------------
        register("dull_blade", COMMON, 3, null, CURSE, SWORD, AXE);
        register("clumsy_swing", COMMON, 3, null, CURSE, SWORD, AXE);
        register("brittle", COMMON, 3, null, CURSE, SWORD, AXE, PICKAXE, SHOVEL, HOE);
        register("weak_draw", COMMON, 3, null, CURSE, BOW, CROSSBOW);
        register("sluggish_shot", COMMON, 2, null, CURSE, BOW, CROSSBOW);
        register("thin_padding", COMMON, 3, "ward", CURSE, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("exposed", COMMON, 3, null, CURSE, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("heavy_hull", COMMON, 3, null, CURSE, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("fire_prone", COMMON, 3, null, CURSE, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("fragile_bones", COMMON, 3, null, CURSE, BOOTS);
        register("dull_pick", COMMON, 3, null, CURSE, PICKAXE);
        register("jinxed", COMMON, 3, null, CURSE, PICKAXE, SHOVEL, HOE, AXE);
        register("gluttony", COMMON, 3, null, CURSE, ANY);
        register("slow_learner", COMMON, 3, null, CURSE, ANY);
        register("cursed_blood", RARE, 3, null, CURSE, ANY);
    }

    private EnchantRegistry() {
    }

    private static void register(String id, EnchantDef.Rarity rarity, int maxLevel,
                                 String conflictGroup, EnchantDef.Polarity polarity,
                                 ItemGroup first, ItemGroup... rest) {
        Set<ItemGroup> supports = rest.length == 0
                ? Set.of(first)
                : Collections.unmodifiableSet(EnumSet.of(first, rest));
        REGISTRY.put(id, new EnchantDef(id, rarity, maxLevel, conflictGroup, supports, polarity));
    }

    private static void register(String id, EnchantDef.Rarity rarity, int maxLevel,
                                 String conflictGroup, EnchantDef.Polarity polarity,
                                 Set<ItemGroup> supports) {
        REGISTRY.put(id, new EnchantDef(id, rarity, maxLevel, conflictGroup,
                Collections.unmodifiableSet(EnumSet.copyOf(supports)), polarity));
    }

    public static EnchantDef byId(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(java.util.Locale.ROOT).trim());
    }

    public static List<EnchantDef> all() {
        return List.copyOf(new ArrayList<>(REGISTRY.values()));
    }

    /** Только светлые чары (в порядке реестра). */
    public static List<EnchantDef> light() {
        return filter(false);
    }

    /** Только проклятия. */
    public static List<EnchantDef> curses() {
        return filter(true);
    }

    public static int size() {
        return REGISTRY.size();
    }

    public static int lightCount() {
        return light().size();
    }

    public static int curseCount() {
        return curses().size();
    }

    private static List<EnchantDef> filter(boolean cursed) {
        List<EnchantDef> result = new ArrayList<>();
        for (EnchantDef def : REGISTRY.values()) {
            if (def.curse() == cursed) {
                result.add(def);
            }
        }
        return List.copyOf(result);
    }
}
