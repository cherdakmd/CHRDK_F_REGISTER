package ru.cherdakmd.chrdkskills.tree;

import java.util.HashMap;
import java.util.Map;

/**
 * Неизменяемое состояние дерева одного игрока в одном домене.
 *
 * <p>Все операции возвращают новое состояние: каркас не знает ни про Bukkit,
 * ни про файлы, поэтому его можно проверить обычными JVM-тестами, а запись в
 * хранилище появится отдельно в Фазе 2.</p>
 *
 * @param level            уровень домена (источник токенов и тир-гейтов)
 * @param coreLevel        купленные уровни ядра; при сбросе сохраняются
 * @param powder           текущий запас пыли
 * @param powderSpent      всего потрачено пыли, включая ядро
 * @param corePowderSpent  потрачено на ядро; при сбросе НЕ возвращается
 * @param tokensSpent      потрачено токенов; при сбросе обнуляется
 * @param levels           ид узла → купленный уровень
 * @param selectedAbility  ид выбранного активного приёма (не более одного)
 */
public record PerkTreeState(TreeDomain domain, int level, int coreLevel, long powder,
                            long powderSpent, long corePowderSpent, int tokensSpent,
                            Map<String, Integer> levels, String selectedAbility) {

    public PerkTreeState {
        if (domain == null) {
            throw new IllegalArgumentException("домен обязателен");
        }
        level = Math.max(0, level);
        coreLevel = Math.max(0, coreLevel);
        powder = Math.max(0L, powder);
        powderSpent = Math.max(0L, powderSpent);
        corePowderSpent = Math.max(0L, corePowderSpent);
        tokensSpent = Math.max(0, tokensSpent);
        levels = levels == null ? Map.of() : Map.copyOf(levels);
        if (selectedAbility != null && selectedAbility.isBlank()) {
            selectedAbility = null;
        }
    }

    public static PerkTreeState empty(TreeDomain domain) {
        return new PerkTreeState(domain, 0, 0, 0L, 0L, 0L, 0, Map.of(), null);
    }

    public int levelOf(String nodeId) {
        return levels.getOrDefault(nodeId, 0);
    }

    public boolean isUnlocked(String nodeId) {
        return levelOf(nodeId) > 0;
    }

    public int tokensAvailable() {
        return PerkTreeMath.tokensEarned(level, coreLevel) - tokensSpent;
    }

    public long refundablePowder() {
        return Math.max(0L, powderSpent - corePowderSpent);
    }

    public PerkTreeState withLevel(int newLevel) {
        return new PerkTreeState(domain, newLevel, coreLevel, powder, powderSpent,
                corePowderSpent, tokensSpent, levels, selectedAbility);
    }

    /** Начисление пыли с потолком запаса; лишнее отбрасывается, а не копится. */
    public PerkTreeState creditPowder(long earned, long cap) {
        long credited = PerkTreeMath.creditedPowder(powder, earned, cap);
        if (credited <= 0L) {
            return this;
        }
        return new PerkTreeState(domain, level, coreLevel, powder + credited, powderSpent,
                corePowderSpent, tokensSpent, levels, selectedAbility);
    }

    /** Покупка одного уровня узла. Все проверки до изменения состояния. */
    public PerkTreeState unlock(PerkTreeDefinition definition, String nodeId) {
        if (definition.domain() != domain) {
            throw new IllegalArgumentException("дефиниция другого домена");
        }
        PerkNode node = definition.node(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("нет узла " + nodeId));
        if (node.tier() > PerkTreeMath.availableTier(level, definition.tiers())) {
            throw new IllegalStateException("тир " + node.tier() + " ещё закрыт");
        }
        for (String required : node.requires()) {
            if (!isUnlocked(required)) {
                throw new IllegalStateException("сначала откройте " + required);
            }
        }
        int current = levelOf(nodeId);
        if (current >= node.maxLevel()) {
            throw new IllegalStateException("узел " + nodeId + " уже на максимуме");
        }
        if (tokensAvailable() < node.tokenCost()) {
            throw new IllegalStateException("не хватает токенов для " + nodeId);
        }
        long cost = PerkTreeMath.levelCost(node, current);
        if (powder < cost) {
            throw new IllegalStateException("не хватает пыли для " + nodeId);
        }
        Map<String, Integer> next = new HashMap<>(levels);
        next.put(nodeId, current + 1);
        return new PerkTreeState(domain, level, coreLevel + (node.core() ? 1 : 0),
                powder - cost, powderSpent + cost,
                corePowderSpent + (node.core() ? cost : 0L),
                tokensSpent + node.tokenCost(), Map.copyOf(next), selectedAbility);
    }

    /** Выбор активного приёма: открытых может быть несколько, выбран — один. */
    public PerkTreeState selectAbility(PerkTreeDefinition definition, String nodeId) {
        PerkNode node = definition.node(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("нет узла " + nodeId));
        if (!node.ability()) {
            throw new IllegalArgumentException("узел " + nodeId + " не активный приём");
        }
        if (!isUnlocked(nodeId)) {
            throw new IllegalStateException("приём " + nodeId + " не открыт");
        }
        return new PerkTreeState(domain, level, coreLevel, powder, powderSpent,
                corePowderSpent, tokensSpent, levels, nodeId);
    }

    /**
     * Сброс: уровень домена и ядро остаются, остальные узлы стираются,
     * токены и пыль за них возвращаются. Пыль ядра не возвращается.
     */
    public PerkTreeState reset() {
        long refund = refundablePowder();
        return new PerkTreeState(domain, level, coreLevel, powder + refund,
                corePowderSpent, corePowderSpent, 0, Map.of(), null);
    }
}
