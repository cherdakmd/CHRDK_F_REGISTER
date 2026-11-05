package ru.cherdakmd.chrdkskills.tree;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ru.cherdakmd.chrdkskills.tree.PerkEffect.ABILITY_DURATION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.AREA_RADIUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.COOLDOWN_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.HUD_DETAIL;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.REACH_BONUS;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.RESET_FEE_WAIVER;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.TOOL_WEAR_REDUCTION;
import static ru.cherdakmd.chrdkskills.tree.PerkEffect.VEIN_PREVIEW;

/**
 * Реестр деревьев. Сейчас зарегистрирован только пилот {@link TreeDomain#MINING}.
 *
 * <p>Остальные 12 доменов зарезервированы: чтобы добавить дерево рыбалки или
 * ремесла, достаточно положить сюда ещё одну {@link PerkTreeDefinition} —
 * ядро, математика и состояние меняться не должны. Это и есть ответ на
 * требование «подобные системы нужны не только в майнинге».</p>
 */
public final class PerkTreeRegistry {

    private static final Map<TreeDomain, PerkTreeDefinition> DEFINITIONS =
            new EnumMap<>(TreeDomain.class);

    static {
        DEFINITIONS.put(TreeDomain.MINING, mining());
    }

    private PerkTreeRegistry() {
    }

    public static Optional<PerkTreeDefinition> of(TreeDomain domain) {
        return Optional.ofNullable(DEFINITIONS.get(domain));
    }

    public static PerkTreeDefinition pilot() {
        return DEFINITIONS.get(TreeDomain.MINING);
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

    /** Явная проверка всех зарегистрированных дефиниций при старте плагина. */
    public static void validateAll() {
        for (Map.Entry<TreeDomain, PerkTreeDefinition> entry : DEFINITIONS.entrySet()) {
            if (entry.getValue().domain() != entry.getKey() || entry.getValue().size() == 0) {
                throw new IllegalStateException("дерево " + entry.getKey().id() + " испорчено");
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
}
