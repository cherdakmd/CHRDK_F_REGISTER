package ru.cherdakmd.chrdkskills.tree;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ABILITY_DURATION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ALLY_HUD;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ALLY_PING;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.AREA_RADIUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.COMBAT_HUD;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.COOLDOWN_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.CRAFT_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.DEATH_RECAP;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.DODGE_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.DURABILITY_ALERT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.EFFECT_TIMER;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ELITE_TELEGRAPH;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ENCHANT_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.HUD_DETAIL;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.LOOT_FILTER_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.LOOT_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.MAP_DETAIL;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.MOB_THREAT_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.PRICE_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.REACH_BONUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RECIPE_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESET_FEE_WAIVER;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESET_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESPAWN_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.SPAWN_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TARGET_CYCLE;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TOOL_WEAR_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TRACK_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TRAJECTORY_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TREE_GUIDE;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.VEIN_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.WAYPOINT_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.WEATHER_HINT;

/**
 * Реестр деревьев.
 *
 * <p>Зарегистрировано шесть деревьев: пилот {@link TreeDomain#MINING} и пять
 * PvE-доменов. Все они используют одно ядро, одну математику и одно состояние;
 * майнинг не выделен ничем, кроме флага {@code pilot}.</p>
 *
 * <p>Каждое дерево — <b>10 тиров по два узла</b> (20 узлов): две ветви с
 * зависимостями, четыре активных приёма на выбор и одно ядро, которое даёт
 * дополнительные токены и сохраняется при сбросе. Часть узлов стоит только пыль
 * ({@code tokenCost == 0}) — это альтернативный путь вложений, когда токены
 * уже потрачены. Все эффекты строго QoL-only: урон, защита, дроп, опыт и
 * скорость добычи запрещены {@link PerkNode}.</p>
 *
 * <p>Остальные домены зарезервированы: чтобы добавить дерево рыбалки или
 * ремесла, достаточно положить сюда ещё одну {@link PerkTreeDefinition}.</p>
 */
public final class PerkTreeRegistry {

    private static final Map<TreeDomain, PerkTreeDefinition> DEFINITIONS =
            new EnumMap<>(TreeDomain.class);

    static {
        DEFINITIONS.put(TreeDomain.MINING, mining());
        DEFINITIONS.put(TreeDomain.COMBAT, combat());
        DEFINITIONS.put(TreeDomain.ARCHERY, archery());
        DEFINITIONS.put(TreeDomain.DEFENSE, defense());
        DEFINITIONS.put(TreeDomain.EXPLORATION, exploration());
        DEFINITIONS.put(TreeDomain.HUNT, hunt());
    }

    private PerkTreeRegistry() {
    }

    public static Optional<PerkTreeDefinition> of(TreeDomain domain) {
        return Optional.ofNullable(DEFINITIONS.get(domain));
    }

    public static PerkTreeDefinition pilot() {
        return DEFINITIONS.get(TreeDomain.MINING);
    }

    /** Зарегистрированные PvE-домены: цель «максимально развивать PvE». */
    public static List<TreeDomain> pveDomains() {
        return List.of(TreeDomain.COMBAT, TreeDomain.ARCHERY, TreeDomain.DEFENSE,
                TreeDomain.EXPLORATION, TreeDomain.HUNT);
    }

    public static Set<TreeDomain> defined() {
        return Set.copyOf(DEFINITIONS.keySet());
    }

    /** Домены без дефиниции: очереди Фаз 2–3. */
    public static List<TreeDomain> reserved() {
        List<TreeDomain> result = new ArrayList<>();
        for (TreeDomain domain : TreeDomain.values()) {
            if (!DEFINITIONS.containsKey(domain)) {
                result.add(domain);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Явная проверка всех дефиниций при старте плагина: не только целостность,
     * но и «длинное дерево» — 10 тиров, по два узла на тир, четыре приёма,
     * одно ядро и ни одного балансового эффекта.
     */
    public static void validateAll() {
        for (Map.Entry<TreeDomain, PerkTreeDefinition> entry : DEFINITIONS.entrySet()) {
            PerkTreeDefinition definition = entry.getValue();
            String id = entry.getKey().id();
            if (definition.domain() != entry.getKey() || definition.size() == 0) {
                throw new IllegalStateException("дерево " + id + " испорчено");
            }
            if (definition.tiers() != 10) {
                throw new IllegalStateException("у дерева " + id + " должно быть 10 тиров");
            }
            if (definition.size() < 20) {
                throw new IllegalStateException("дерево " + id + " слишком короткое");
            }
            for (int tier = 1; tier <= definition.tiers(); tier++) {
                if (definition.nodesAtTier(tier).size() < 2) {
                    throw new IllegalStateException("у дерева " + id + " тир " + tier
                            + " должен давать выбор из двух узлов");
                }
            }
            if (definition.coreNodes().size() != 1 || definition.abilities().size() < 4) {
                throw new IllegalStateException("у дерева " + id
                        + " должно быть одно ядро и не менее четырёх приёмов");
            }
            for (PerkNode node : definition.nodes()) {
                if (node.effect().balanceSensitive()) {
                    throw new IllegalStateException("балансовый эффект в дереве " + id
                            + ": " + node.id());
                }
                if (node.core() && node.ability()) {
                    throw new IllegalStateException("ядро не может быть приёмом: " + node.id());
                }
            }
        }
    }

    private static PerkTreeDefinition mining() {
        TreeDomain domain = TreeDomain.MINING;
        List<PerkNode> nodes = List.of(
                new PerkNode("mining-vein-preview", domain, 1, 1, 50, VEIN_PREVIEW, 1.0),
                new PerkNode("mining-tool-care", domain, 1, 1, 50, TOOL_WEAR_REDUCTION, 0.05, 3, Set.of(), false, false),
                new PerkNode("mining-burst", domain, 2, 2, 120, ABILITY_DURATION, 10.0, 1, Set.of(), false, true),
                new PerkNode("mining-pickobulus", domain, 2, 2, 150, AREA_RADIUS, 2.0, 1, Set.of(), false, true),
                new PerkNode("mining-hud", domain, 3, 1, 90, HUD_DETAIL, 1.0, 1, Set.of("mining-vein-preview"), false, false),
                new PerkNode("mining-cooldown", domain, 3, 1, 110, COOLDOWN_REDUCTION, 0.05, 3, Set.of("mining-burst"), false, false),
                new PerkNode("mining-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 3, Set.of("mining-hud"), true, false),
                new PerkNode("mining-reach", domain, 5, 1, 180, REACH_BONUS, 1.0, 2, Set.of("mining-core"), false, false),
                new PerkNode("mining-vein-map", domain, 6, 2, 240, VEIN_PREVIEW, 2.0, 2, Set.of("mining-cooldown"), false, false),
                new PerkNode("mining-second-wind", domain, 7, 2, 400, ABILITY_DURATION, 15.0, 1, Set.of("mining-reach"), false, true),
                new PerkNode("mining-ore-ledger", domain, 4, 1, 160, LOOT_FILTER_HINT, 1.0, 2, Set.of("mining-hud"), false, false),
                new PerkNode("mining-vein-echo", domain, 5, 1, 200, MAP_DETAIL, 1.0, 2, Set.of("mining-ore-ledger"), false, false),
                new PerkNode("mining-depth-hint", domain, 6, 1, 220, WEATHER_HINT, 1.0, 2, Set.of("mining-vein-map"), false, false),
                new PerkNode("mining-salvage-eye", domain, 7, 1, 260, ENCHANT_PREVIEW, 1.0, 2, Set.of("mining-second-wind"), false, false),
                new PerkNode("mining-deep-pulse", domain, 8, 2, 300, ABILITY_DURATION, 20.0, 1, Set.of("mining-salvage-eye"), false, true),
                new PerkNode("mining-tool-diary", domain, 8, 0, 240, EFFECT_TIMER, 1.0, 2, Set.of("mining-vein-echo"), false, false),
                new PerkNode("mining-ore-price", domain, 9, 1, 320, PRICE_HINT, 1.0, 2, Set.of("mining-depth-hint"), false, false),
                new PerkNode("mining-grand-vein", domain, 9, 1, 340, AREA_RADIUS, 3.0, 2, Set.of("mining-deep-pulse"), false, false),
                new PerkNode("mining-master-hud", domain, 10, 2, 420, HUD_DETAIL, 2.0, 1, Set.of("mining-ore-price", "mining-grand-vein"), false, false),
                new PerkNode("mining-last-seam", domain, 10, 1, 420, TRACK_HINT, 2.0, 2, Set.of("mining-tool-diary"), false, false));
        return new PerkTreeDefinition(domain, 10, 2000L, nodes);
    }

    private static PerkTreeDefinition combat() {
        TreeDomain domain = TreeDomain.COMBAT;
        List<PerkNode> nodes = List.of(
                new PerkNode("combat-threat-hint", domain, 1, 1, 50, MOB_THREAT_HINT, 1.0),
                new PerkNode("combat-durability-alert", domain, 1, 1, 50, DURABILITY_ALERT, 1.0, 3, Set.of(), false, false),
                new PerkNode("combat-focus", domain, 2, 2, 120, TARGET_CYCLE, 1.0, 1, Set.of(), false, true),
                new PerkNode("combat-hud", domain, 3, 1, 90, COMBAT_HUD, 1.0, 1, Set.of("combat-threat-hint"), false, false),
                new PerkNode("combat-dodge-hint", domain, 3, 1, 110, DODGE_HINT, 1.0, 3, Set.of("combat-focus"), false, false),
                new PerkNode("combat-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 3, Set.of("combat-hud"), true, false),
                new PerkNode("combat-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0, 2, Set.of("combat-core"), false, false),
                new PerkNode("combat-elite-telegraph", domain, 6, 2, 240, ELITE_TELEGRAPH, 1.0, 2, Set.of("combat-dodge-hint"), false, false),
                new PerkNode("combat-second-wind", domain, 7, 2, 400, ABILITY_DURATION, 12.0, 1, Set.of("combat-loot-preview"), false, true),
                new PerkNode("combat-riposte-cue", domain, 2, 2, 130, DODGE_HINT, 1.0, 1, Set.of(), false, true),
                new PerkNode("combat-death-recap", domain, 4, 1, 170, DEATH_RECAP, 1.0, 2, Set.of("combat-hud"), false, false),
                new PerkNode("combat-timer-hud", domain, 5, 0, 200, EFFECT_TIMER, 1.0, 2, Set.of("combat-death-recap"), false, false),
                new PerkNode("combat-spawn-cue", domain, 6, 1, 250, SPAWN_HINT, 1.0, 2, Set.of("combat-elite-telegraph"), false, false),
                new PerkNode("combat-ally-hud", domain, 7, 1, 280, ALLY_HUD, 1.0, 2, Set.of("combat-second-wind"), false, false),
                new PerkNode("combat-war-cry", domain, 8, 2, 320, ABILITY_DURATION, 18.0, 1, Set.of("combat-ally-hud"), false, true),
                new PerkNode("combat-loot-filter", domain, 8, 0, 260, LOOT_FILTER_HINT, 1.0, 2, Set.of("combat-timer-hud"), false, false),
                new PerkNode("combat-weather-eye", domain, 9, 1, 340, WEATHER_HINT, 1.0, 2, Set.of("combat-spawn-cue"), false, false),
                new PerkNode("combat-master-recap", domain, 9, 1, 360, DEATH_RECAP, 2.0, 2, Set.of("combat-war-cry"), false, false),
                new PerkNode("combat-arena-mind", domain, 10, 2, 440, COMBAT_HUD, 2.0, 1, Set.of("combat-master-recap", "combat-loot-filter"), false, false),
                new PerkNode("combat-final-stance", domain, 10, 1, 440, DODGE_HINT, 3.0, 2, Set.of("combat-weather-eye"), false, false));
        return new PerkTreeDefinition(domain, 10, 2000L, nodes);
    }

    private static PerkTreeDefinition archery() {
        TreeDomain domain = TreeDomain.ARCHERY;
        List<PerkNode> nodes = List.of(
                new PerkNode("archery-trajectory", domain, 1, 1, 50, TRAJECTORY_HINT, 1.0),
                new PerkNode("archery-quiver-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0, 2, Set.of(), false, false),
                new PerkNode("archery-focus", domain, 2, 2, 120, TARGET_CYCLE, 1.0, 1, Set.of(), false, true),
                new PerkNode("archery-range-marker", domain, 3, 1, 110, REACH_BONUS, 1.0, 2, Set.of("archery-trajectory"), false, false),
                new PerkNode("archery-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 2, Set.of("archery-quiver-hud"), true, false),
                new PerkNode("archery-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0, 1, Set.of("archery-core"), false, false),
                new PerkNode("archery-wind-cue", domain, 2, 2, 130, WEATHER_HINT, 1.0, 1, Set.of(), false, true),
                new PerkNode("archery-effect-timer", domain, 3, 1, 120, EFFECT_TIMER, 1.0, 2, Set.of("archery-range-marker"), false, false),
                new PerkNode("archery-craft-preview", domain, 4, 0, 170, CRAFT_PREVIEW, 1.0, 2, Set.of("archery-quiver-hud"), false, false),
                new PerkNode("archery-spawn-cue", domain, 5, 1, 190, SPAWN_HINT, 1.0, 2, Set.of("archery-loot-preview"), false, false),
                new PerkNode("archery-volley", domain, 6, 2, 260, ABILITY_DURATION, 14.0, 1, Set.of("archery-effect-timer"), false, true),
                new PerkNode("archery-map-detail", domain, 6, 0, 220, MAP_DETAIL, 1.0, 2, Set.of("archery-craft-preview"), false, false),
                new PerkNode("archery-price-cue", domain, 7, 1, 300, PRICE_HINT, 1.0, 2, Set.of("archery-spawn-cue"), false, false),
                new PerkNode("archery-ally-hud", domain, 7, 1, 300, ALLY_HUD, 1.0, 2, Set.of("archery-volley"), false, false),
                new PerkNode("archery-eagle-eye", domain, 8, 1, 340, REACH_BONUS, 2.0, 2, Set.of("archery-map-detail"), false, false),
                new PerkNode("archery-loot-filter", domain, 8, 0, 280, LOOT_FILTER_HINT, 1.0, 2, Set.of("archery-price-cue"), false, false),
                new PerkNode("archery-reset-preview", domain, 9, 1, 360, RESET_PREVIEW, 1.0, 2, Set.of("archery-ally-hud"), false, false),
                new PerkNode("archery-tree-guide", domain, 9, 1, 360, TREE_GUIDE, 1.0, 2, Set.of("archery-eagle-eye"), false, false),
                new PerkNode("archery-master-shot", domain, 10, 2, 450, TRAJECTORY_HINT, 3.0, 1, Set.of("archery-tree-guide", "archery-reset-preview"), false, true),
                new PerkNode("archery-last-mark", domain, 10, 1, 450, TRACK_HINT, 2.0, 2, Set.of("archery-loot-filter"), false, false));
        return new PerkTreeDefinition(domain, 10, 1500L, nodes);
    }

    private static PerkTreeDefinition defense() {
        TreeDomain domain = TreeDomain.DEFENSE;
        List<PerkNode> nodes = List.of(
                new PerkNode("defense-damage-log", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("defense-shield-alert", domain, 1, 1, 50, DURABILITY_ALERT, 1.0, 3, Set.of(), false, false),
                new PerkNode("defense-telegraph", domain, 2, 2, 120, ELITE_TELEGRAPH, 1.0, 1, Set.of(), false, true),
                new PerkNode("defense-dodge-hint", domain, 3, 1, 110, DODGE_HINT, 1.0, 3, Set.of("defense-damage-log"), false, false),
                new PerkNode("defense-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 2, Set.of("defense-dodge-hint"), true, false),
                new PerkNode("defense-respawn-hint", domain, 5, 1, 180, RESPAWN_HINT, 1.0, 1, Set.of("defense-core"), false, false),
                new PerkNode("defense-guard-cue", domain, 2, 2, 130, DODGE_HINT, 1.0, 1, Set.of(), false, true),
                new PerkNode("defense-death-recap", domain, 3, 1, 120, DEATH_RECAP, 1.0, 2, Set.of("defense-dodge-hint"), false, false),
                new PerkNode("defense-effect-timer", domain, 4, 0, 170, EFFECT_TIMER, 1.0, 2, Set.of("defense-shield-alert"), false, false),
                new PerkNode("defense-spawn-cue", domain, 5, 1, 190, SPAWN_HINT, 1.0, 2, Set.of("defense-respawn-hint"), false, false),
                new PerkNode("defense-bulwark", domain, 6, 2, 260, ABILITY_DURATION, 16.0, 1, Set.of("defense-death-recap"), false, true),
                new PerkNode("defense-map-detail", domain, 6, 0, 220, MAP_DETAIL, 1.0, 2, Set.of("defense-effect-timer"), false, false),
                new PerkNode("defense-ally-hud", domain, 7, 1, 300, ALLY_HUD, 1.0, 2, Set.of("defense-spawn-cue"), false, false),
                new PerkNode("defense-loot-filter", domain, 7, 0, 250, LOOT_FILTER_HINT, 1.0, 2, Set.of("defense-bulwark"), false, false),
                new PerkNode("defense-weather-eye", domain, 8, 1, 340, WEATHER_HINT, 1.0, 2, Set.of("defense-map-detail"), false, false),
                new PerkNode("defense-price-cue", domain, 8, 1, 320, PRICE_HINT, 1.0, 2, Set.of("defense-ally-hud"), false, false),
                new PerkNode("defense-reset-preview", domain, 9, 1, 360, RESET_PREVIEW, 1.0, 2, Set.of("defense-weather-eye"), false, false),
                new PerkNode("defense-craft-preview", domain, 9, 0, 300, CRAFT_PREVIEW, 1.0, 2, Set.of("defense-price-cue"), false, false),
                new PerkNode("defense-master-ward", domain, 10, 2, 450, COMBAT_HUD, 2.0, 1, Set.of("defense-craft-preview", "defense-reset-preview"), false, true),
                new PerkNode("defense-last-stand", domain, 10, 1, 450, RESPAWN_HINT, 2.0, 2, Set.of("defense-loot-filter"), false, false));
        return new PerkTreeDefinition(domain, 10, 1500L, nodes);
    }

    private static PerkTreeDefinition exploration() {
        TreeDomain domain = TreeDomain.EXPLORATION;
        List<PerkNode> nodes = List.of(
                new PerkNode("explore-biome-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("explore-waypoint", domain, 1, 1, 50, WAYPOINT_HINT, 1.0, 3, Set.of(), false, false),
                new PerkNode("explore-scout-ping", domain, 2, 2, 120, ALLY_PING, 1.0, 1, Set.of(), false, true),
                new PerkNode("explore-threat-hint", domain, 3, 1, 110, MOB_THREAT_HINT, 1.0, 1, Set.of("explore-biome-hud"), false, false),
                new PerkNode("explore-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 2, Set.of("explore-threat-hint"), true, false),
                new PerkNode("explore-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0, 1, Set.of("explore-core"), false, false),
                new PerkNode("explore-map-cue", domain, 2, 2, 130, MAP_DETAIL, 1.0, 1, Set.of(), false, true),
                new PerkNode("explore-weather-eye", domain, 3, 1, 120, WEATHER_HINT, 1.0, 2, Set.of("explore-threat-hint"), false, false),
                new PerkNode("explore-effect-timer", domain, 4, 0, 170, EFFECT_TIMER, 1.0, 2, Set.of("explore-waypoint"), false, false),
                new PerkNode("explore-recipe-hint", domain, 5, 1, 190, RECIPE_HINT, 1.0, 2, Set.of("explore-loot-preview"), false, false),
                new PerkNode("explore-spawn-cue", domain, 6, 2, 260, SPAWN_HINT, 1.0, 1, Set.of("explore-weather-eye"), false, true),
                new PerkNode("explore-track-hint", domain, 6, 0, 220, TRACK_HINT, 1.0, 2, Set.of("explore-effect-timer"), false, false),
                new PerkNode("explore-price-cue", domain, 7, 1, 300, PRICE_HINT, 1.0, 2, Set.of("explore-recipe-hint"), false, false),
                new PerkNode("explore-ally-hud", domain, 7, 1, 300, ALLY_HUD, 1.0, 2, Set.of("explore-spawn-cue"), false, false),
                new PerkNode("explore-craft-preview", domain, 8, 1, 340, CRAFT_PREVIEW, 1.0, 2, Set.of("explore-track-hint"), false, false),
                new PerkNode("explore-loot-filter", domain, 8, 0, 280, LOOT_FILTER_HINT, 1.0, 2, Set.of("explore-price-cue"), false, false),
                new PerkNode("explore-enchant-preview", domain, 9, 1, 360, ENCHANT_PREVIEW, 1.0, 2, Set.of("explore-ally-hud"), false, false),
                new PerkNode("explore-reset-preview", domain, 9, 0, 300, RESET_PREVIEW, 1.0, 2, Set.of("explore-craft-preview"), false, false),
                new PerkNode("explore-master-map", domain, 10, 2, 450, MAP_DETAIL, 3.0, 1, Set.of("explore-enchant-preview", "explore-reset-preview"), false, true),
                new PerkNode("explore-last-camp", domain, 10, 1, 450, TREE_GUIDE, 2.0, 2, Set.of("explore-loot-filter"), false, false));
        return new PerkTreeDefinition(domain, 10, 1500L, nodes);
    }

    private static PerkTreeDefinition hunt() {
        TreeDomain domain = TreeDomain.HUNT;
        List<PerkNode> nodes = List.of(
                new PerkNode("hunt-track-hint", domain, 1, 1, 50, MOB_THREAT_HINT, 1.0),
                new PerkNode("hunt-trophy-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("hunt-mark", domain, 2, 2, 120, TARGET_CYCLE, 1.0, 1, Set.of(), false, true),
                new PerkNode("hunt-loot-preview", domain, 3, 1, 110, LOOT_PREVIEW, 1.0, 1, Set.of("hunt-track-hint"), false, false),
                new PerkNode("hunt-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0, 2, Set.of("hunt-trophy-hud"), true, false),
                new PerkNode("hunt-ally-ping", domain, 5, 1, 180, ALLY_PING, 1.0, 1, Set.of("hunt-core"), false, false),
                new PerkNode("hunt-pack-cue", domain, 2, 2, 130, ALLY_PING, 1.0, 1, Set.of(), false, true),
                new PerkNode("hunt-spawn-cue", domain, 3, 1, 120, SPAWN_HINT, 1.0, 2, Set.of("hunt-loot-preview"), false, false),
                new PerkNode("hunt-effect-timer", domain, 4, 0, 170, EFFECT_TIMER, 1.0, 2, Set.of("hunt-trophy-hud"), false, false),
                new PerkNode("hunt-weather-eye", domain, 5, 1, 190, WEATHER_HINT, 1.0, 2, Set.of("hunt-ally-ping"), false, false),
                new PerkNode("hunt-blood-trail", domain, 6, 2, 260, TRACK_HINT, 1.0, 1, Set.of("hunt-spawn-cue"), false, true),
                new PerkNode("hunt-craft-preview", domain, 6, 0, 220, CRAFT_PREVIEW, 1.0, 2, Set.of("hunt-effect-timer"), false, false),
                new PerkNode("hunt-price-cue", domain, 7, 1, 300, PRICE_HINT, 1.0, 2, Set.of("hunt-weather-eye"), false, false),
                new PerkNode("hunt-ally-hud", domain, 7, 1, 300, ALLY_HUD, 1.0, 2, Set.of("hunt-blood-trail"), false, false),
                new PerkNode("hunt-loot-filter", domain, 8, 1, 340, LOOT_FILTER_HINT, 1.0, 2, Set.of("hunt-craft-preview"), false, false),
                new PerkNode("hunt-death-recap", domain, 8, 0, 280, DEATH_RECAP, 1.0, 2, Set.of("hunt-price-cue"), false, false),
                new PerkNode("hunt-map-detail", domain, 9, 1, 360, MAP_DETAIL, 1.0, 2, Set.of("hunt-ally-hud"), false, false),
                new PerkNode("hunt-reset-preview", domain, 9, 0, 300, RESET_PREVIEW, 1.0, 2, Set.of("hunt-loot-filter"), false, false),
                new PerkNode("hunt-master-tracker", domain, 10, 2, 450, TREE_GUIDE, 2.0, 1, Set.of("hunt-map-detail", "hunt-reset-preview"), false, true),
                new PerkNode("hunt-last-trophy", domain, 10, 1, 450, LOOT_PREVIEW, 3.0, 2, Set.of("hunt-death-recap"), false, false));
        return new PerkTreeDefinition(domain, 10, 1500L, nodes);
    }

}
