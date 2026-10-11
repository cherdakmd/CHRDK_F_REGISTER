package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.cherdakmd.chrdkenchants.ItemGroup.AXE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.BOOTS;
import static ru.cherdakmd.chrdkenchants.ItemGroup.BOW;
import static ru.cherdakmd.chrdkenchants.ItemGroup.CHESTPLATE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.CROSSBOW;
import static ru.cherdakmd.chrdkenchants.ItemGroup.HELMET;
import static ru.cherdakmd.chrdkenchants.ItemGroup.LEGGINGS;
import static ru.cherdakmd.chrdkenchants.ItemGroup.PICKAXE;
import static ru.cherdakmd.chrdkenchants.ItemGroup.SHOVEL;
import static ru.cherdakmd.chrdkenchants.ItemGroup.SWORD;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.COMMON;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.EPIC;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.LEGENDARY;
import static ru.cherdakmd.chrdkenchants.EnchantDef.Rarity.RARE;

/** Реестр всех 30 кастомных чар (статический каталог, параметры в коде, вкл/выкл из конфига). */
public final class EnchantRegistry {

    private static final Map<String, EnchantDef> REGISTRY = new LinkedHashMap<>();

    static {
        register("thunderstrike", RARE, 3, "lightning", SWORD, AXE);
        register("lifesteal", EPIC, 3, null, SWORD, AXE);
        register("chainspark", EPIC, 2, "lightning", SWORD, AXE);
        register("bleeding", RARE, 3, null, SWORD, AXE);
        register("paralyze", EPIC, 2, null, SWORD, AXE);
        register("execution", LEGENDARY, 2, null, SWORD, AXE);
        register("berserk", RARE, 3, null, SWORD, AXE);
        register("slayer", RARE, 2, null, SWORD, AXE);
        register("souleater", EPIC, 2, null, SWORD, AXE);
        register("stormarrow", RARE, 3, "impact-aoe", BOW, CROSSBOW);
        register("explosive", EPIC, 2, "impact-aoe", BOW, CROSSBOW);
        register("sniper", RARE, 3, null, BOW, CROSSBOW);
        register("frostarrow", RARE, 2, null, BOW, CROSSBOW);
        register("tracer", COMMON, 1, null, BOW, CROSSBOW);
        register("headhunter", LEGENDARY, 1, null, BOW, CROSSBOW);
        register("retribution", EPIC, 3, "aura", HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("laststand", LEGENDARY, 1, "savior", CHESTPLATE, LEGGINGS);
        register("fortified", RARE, 3, null, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("lightningrod", COMMON, 1, null, HELMET, CHESTPLATE, LEGGINGS, BOOTS);
        register("phoenix", LEGENDARY, 1, "savior", CHESTPLATE);
        register("iceward", RARE, 2, "aura", LEGGINGS);
        register("truesight", EPIC, 1, null, HELMET);
        register("seer", COMMON, 1, null, HELMET);
        register("swiftfeet", RARE, 2, null, BOOTS);
        register("froggy", COMMON, 2, null, BOOTS);
        register("lavawalker", LEGENDARY, 1, null, BOOTS);
        register("autosmelt", RARE, 1, null, PICKAXE);
        register("veinminer", EPIC, 3, "megamine", PICKAXE);
        register("excavator", EPIC, 2, "megamine", PICKAXE, SHOVEL, AXE);
        register("midas", LEGENDARY, 1, null, PICKAXE);
    }

    private EnchantRegistry() {
    }

    private static void register(String id, EnchantDef.Rarity rarity, int maxLevel,
                                 String conflictGroup, ItemGroup first, ItemGroup... rest) {
        Set<ItemGroup> supports = rest.length == 0
                ? Set.of(first)
                : java.util.EnumSet.of(first, rest);
        REGISTRY.put(id, new EnchantDef(id, rarity, maxLevel, conflictGroup,
                Collections.unmodifiableSet(supports)));
    }

    public static EnchantDef byId(String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(java.util.Locale.ROOT).trim());
    }

    public static List<EnchantDef> all() {
        return List.copyOf(new ArrayList<>(REGISTRY.values()));
    }

    public static int size() {
        return REGISTRY.size();
    }
}
