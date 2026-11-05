package ru.cherdakmd.chrdkskills.tree;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ABILITY_DURATION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ALLY_PING;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.AREA_RADIUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.COMBAT_HUD;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.COOLDOWN_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.DODGE_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.DURABILITY_ALERT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ELITE_TELEGRAPH;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.HUD_DETAIL;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.LOOT_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.MOB_THREAT_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.REACH_BONUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESET_FEE_WAIVER;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESPAWN_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TARGET_CYCLE;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TOOL_WEAR_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TRAJECTORY_HINT;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.VEIN_PREVIEW;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.WAYPOINT_HINT;

/**
 * Реестр деревьев.
 *
 * <p>{@link TreeDomain#MINING} остаётся пилотом для обкатки контракта, но
 * приоритет развития перенесён на PvE: зарегистрированы деревья ближнего боя,
 * стрельбы, защиты, исследования и охоты. Все они используют то же ядро, ту же
 * математику и то же состояние — майнинг не выделен в коде ничем, кроме флага
 * {@code pilot}.</p>
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

    /** Явная проверка всех дефиниций при старте плагина. */
    public static void validateAll() {
        for (Map.Entry<TreeDomain, PerkTreeDefinition> entry : DEFINITIONS.entrySet()) {
            PerkTreeDefinition definition = entry.getValue();
            if (definition.domain() != entry.getKey() || definition.size() == 0) {
                throw new IllegalStateException("дерево " + entry.getKey().id() + " испорчено");
            }
            if (definition.coreNodes().isEmpty() || definition.abilities().isEmpty()) {
                throw new IllegalStateException("у дерева " + entry.getKey().id()
                        + " должно быть ядро и хотя бы один активный приём");
            }
        }
    }

    private static PerkTreeDefinition mining() {
        TreeDomain domain = TreeDomain.MINING;
        List<PerkNode> nodes = List.of(
                new PerkNode("mining-vein-preview", domain, 1, 1, 50, VEIN_PREVIEW, 1.0),
                new PerkNode("mining-tool-care", domain, 1, 1, 50, TOOL_WEAR_REDUCTION, 0.05,
                        3, Set.of(), false, false),
                new PerkNode("mining-burst", domain, 2, 2, 120, ABILITY_DURATION, 10.0,
                        1, Set.of(), false, true),
                new PerkNode("mining-pickobulus", domain, 2, 2, 150, AREA_RADIUS, 2.0,
                        1, Set.of(), false, true),
                new PerkNode("mining-hud", domain, 3, 1, 90, HUD_DETAIL, 1.0,
                        1, Set.of("mining-vein-preview"), false, false),
                new PerkNode("mining-cooldown", domain, 3, 1, 110, COOLDOWN_REDUCTION, 0.05,
                        3, Set.of("mining-burst"), false, false),
                new PerkNode("mining-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        3, Set.of("mining-hud"), true, false),
                new PerkNode("mining-reach", domain, 5, 1, 180, REACH_BONUS, 1.0,
                        2, Set.of("mining-core"), false, false),
                new PerkNode("mining-vein-map", domain, 6, 2, 240, VEIN_PREVIEW, 2.0,
                        2, Set.of("mining-cooldown"), false, false),
                new PerkNode("mining-second-wind", domain, 7, 2, 400, ABILITY_DURATION, 15.0,
                        1, Set.of("mining-reach"), false, true));
        return new PerkTreeDefinition(domain, 7, 2_000L, nodes);
    }

    private static PerkTreeDefinition combat() {
        TreeDomain domain = TreeDomain.COMBAT;
        List<PerkNode> nodes = List.of(
                new PerkNode("combat-threat-hint", domain, 1, 1, 50, MOB_THREAT_HINT, 1.0),
                new PerkNode("combat-durability-alert", domain, 1, 1, 50, DURABILITY_ALERT, 1.0,
                        3, Set.of(), false, false),
                new PerkNode("combat-focus", domain, 2, 2, 120, TARGET_CYCLE, 1.0,
                        1, Set.of(), false, true),
                new PerkNode("combat-hud", domain, 3, 1, 90, COMBAT_HUD, 1.0,
                        1, Set.of("combat-threat-hint"), false, false),
                new PerkNode("combat-dodge-hint", domain, 3, 1, 110, DODGE_HINT, 1.0,
                        3, Set.of("combat-focus"), false, false),
                new PerkNode("combat-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        3, Set.of("combat-hud"), true, false),
                new PerkNode("combat-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0,
                        2, Set.of("combat-core"), false, false),
                new PerkNode("combat-elite-telegraph", domain, 6, 2, 240, ELITE_TELEGRAPH, 1.0,
                        2, Set.of("combat-dodge-hint"), false, false),
                new PerkNode("combat-second-wind", domain, 7, 2, 400, ABILITY_DURATION, 12.0,
                        1, Set.of("combat-loot-preview"), false, true));
        return new PerkTreeDefinition(domain, 7, 2_000L, nodes);
    }

    private static PerkTreeDefinition archery() {
        TreeDomain domain = TreeDomain.ARCHERY;
        List<PerkNode> nodes = List.of(
                new PerkNode("archery-trajectory", domain, 1, 1, 50, TRAJECTORY_HINT, 1.0),
                new PerkNode("archery-quiver-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0,
                        2, Set.of(), false, false),
                new PerkNode("archery-focus", domain, 2, 2, 120, TARGET_CYCLE, 1.0,
                        1, Set.of(), false, true),
                new PerkNode("archery-range-marker", domain, 3, 1, 110, REACH_BONUS, 1.0,
                        2, Set.of("archery-trajectory"), false, false),
                new PerkNode("archery-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        2, Set.of("archery-quiver-hud"), true, false),
                new PerkNode("archery-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0,
                        1, Set.of("archery-core"), false, false));
        return new PerkTreeDefinition(domain, 5, 1_500L, nodes);
    }

    private static PerkTreeDefinition defense() {
        TreeDomain domain = TreeDomain.DEFENSE;
        List<PerkNode> nodes = List.of(
                new PerkNode("defense-damage-log", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("defense-shield-alert", domain, 1, 1, 50, DURABILITY_ALERT, 1.0,
                        3, Set.of(), false, false),
                new PerkNode("defense-telegraph", domain, 2, 2, 120, ELITE_TELEGRAPH, 1.0,
                        1, Set.of(), false, true),
                new PerkNode("defense-dodge-hint", domain, 3, 1, 110, DODGE_HINT, 1.0,
                        3, Set.of("defense-damage-log"), false, false),
                new PerkNode("defense-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        2, Set.of("defense-dodge-hint"), true, false),
                new PerkNode("defense-respawn-hint", domain, 5, 1, 180, RESPAWN_HINT, 1.0,
                        1, Set.of("defense-core"), false, false));
        return new PerkTreeDefinition(domain, 5, 1_500L, nodes);
    }

    private static PerkTreeDefinition exploration() {
        TreeDomain domain = TreeDomain.EXPLORATION;
        List<PerkNode> nodes = List.of(
                new PerkNode("explore-biome-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("explore-waypoint", domain, 1, 1, 50, WAYPOINT_HINT, 1.0,
                        3, Set.of(), false, false),
                new PerkNode("explore-scout-ping", domain, 2, 2, 120, ALLY_PING, 1.0,
                        1, Set.of(), false, true),
                new PerkNode("explore-threat-hint", domain, 3, 1, 110, MOB_THREAT_HINT, 1.0,
                        1, Set.of("explore-biome-hud"), false, false),
                new PerkNode("explore-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        2, Set.of("explore-threat-hint"), true, false),
                new PerkNode("explore-loot-preview", domain, 5, 1, 180, LOOT_PREVIEW, 1.0,
                        1, Set.of("explore-core"), false, false));
        return new PerkTreeDefinition(domain, 5, 1_500L, nodes);
    }

    private static PerkTreeDefinition hunt() {
        TreeDomain domain = TreeDomain.HUNT;
        List<PerkNode> nodes = List.of(
                new PerkNode("hunt-track-hint", domain, 1, 1, 50, MOB_THREAT_HINT, 1.0),
                new PerkNode("hunt-trophy-hud", domain, 1, 1, 50, COMBAT_HUD, 1.0),
                new PerkNode("hunt-mark", domain, 2, 2, 120, TARGET_CYCLE, 1.0,
                        1, Set.of(), false, true),
                new PerkNode("hunt-loot-preview", domain, 3, 1, 110, LOOT_PREVIEW, 1.0,
                        1, Set.of("hunt-track-hint"), false, false),
                new PerkNode("hunt-core", domain, 4, 2, 300, RESET_FEE_WAIVER, 1.0,
                        2, Set.of("hunt-trophy-hud"), true, false),
                new PerkNode("hunt-ally-ping", domain, 5, 1, 180, ALLY_PING, 1.0,
                        1, Set.of("hunt-core"), false, false));
        return new PerkTreeDefinition(domain, 5, 1_500L, nodes);
    }
}
