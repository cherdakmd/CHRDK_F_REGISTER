package ru.cherdakmd.chrdkskills.tree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Дефиниция дерева одного домена: тиры, узлы и потолок запаса пыли.
 *
 * <p>Дефиниция проверяется целиком при создании: уникальные иды, один домен,
 * тир в границах дерева, зависимости существуют и не забегают вперёд. Ошибка
 * конфигурации деревьев должна падать на загрузке, а не в момент покупки узла
 * игроком.</p>
 */
public final class PerkTreeDefinition {

    private final TreeDomain domain;
    private final int tiers;
    private final long powderCap;
    private final List<PerkNode> nodes;
    private final Map<String, PerkNode> byId;

    public PerkTreeDefinition(TreeDomain domain, int tiers, long powderCap, List<PerkNode> nodes) {
        if (domain == null || nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("дерево должно иметь домен и хотя бы один узел");
        }
        if (tiers < 1 || tiers > 10 || powderCap <= 0) {
            throw new IllegalArgumentException("некорректные границы дерева " + domain.id());
        }
        this.domain = domain;
        this.tiers = tiers;
        this.powderCap = powderCap;
        this.nodes = List.copyOf(nodes);
        Map<String, PerkNode> index = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (PerkNode node : this.nodes) {
            if (node.domain() != domain) {
                throw new IllegalArgumentException("узел " + node.id() + " из другого домена");
            }
            if (!seen.add(node.id())) {
                throw new IllegalArgumentException("повтор ида узла: " + node.id());
            }
            if (node.tier() > tiers) {
                throw new IllegalArgumentException("тир узла " + node.id() + " вне дерева");
            }
            index.put(node.id(), node);
        }
        for (PerkNode node : this.nodes) {
            for (String required : node.requires()) {
                PerkNode dependency = index.get(required);
                if (dependency == null) {
                    throw new IllegalArgumentException("нет зависимости " + required + " у " + node.id());
                }
                if (dependency.tier() > node.tier()) {
                    throw new IllegalArgumentException("зависимость " + required + " старше узла " + node.id());
                }
            }
        }
        this.byId = Map.copyOf(index);
    }

    public TreeDomain domain() {
        return domain;
    }

    public int tiers() {
        return tiers;
    }

    public long powderCap() {
        return powderCap;
    }

    public List<PerkNode> nodes() {
        return nodes;
    }

    public int size() {
        return nodes.size();
    }

    public Optional<PerkNode> node(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<PerkNode> nodesAtTier(int tier) {
        List<PerkNode> result = new ArrayList<>();
        for (PerkNode node : nodes) {
            if (node.tier() == tier) {
                result.add(node);
            }
        }
        return List.copyOf(result);
    }

    public List<PerkNode> abilities() {
        return nodes.stream().filter(PerkNode::ability).toList();
    }

    public List<PerkNode> coreNodes() {
        return nodes.stream().filter(PerkNode::core).toList();
    }
}
