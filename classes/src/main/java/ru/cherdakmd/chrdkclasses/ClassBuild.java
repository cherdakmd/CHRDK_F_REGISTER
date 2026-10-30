package ru.cherdakmd.chrdkclasses;

import java.util.*;

/** Сборка не хранит очки и откаты: только распределение и два ID умений. */
record ClassBuild(String classId, Map<String,Integer> ranks, List<String> abilities) {
    ClassBuild {
        if (PlayerClass.parse(classId) == null) throw new IllegalArgumentException("Неизвестный класс сборки");
        ranks = Map.copyOf(ranks);
        abilities = List.copyOf(abilities);
        if (ranks.size() > 45 || abilities.size() != 2 || new HashSet<>(abilities).size() != 2)
            throw new IllegalArgumentException("Некорректный размер сборки");
        for (var entry : ranks.entrySet()) {
            Talent node = ClassTrees.talent(entry.getKey());
            if (node == null || !node.classId().equals(classId) || entry.getValue() < 1 || entry.getValue() > node.maxRank())
                throw new IllegalArgumentException("Некорректный ранг сборки");
        }
        for (String id : abilities) {
            ClassAbility ability = ClassAbility.byKey(id);
            if (ability == null || !ability.id().equals(classId)) throw new IllegalArgumentException("Чужое умение");
        }
    }
    static ClassBuild empty(String role) {
        return new ClassBuild(role, Map.of(), List.of(role, role + "-2"));
    }
    int spent() {
        return ranks.entrySet().stream().mapToInt(e -> ClassTrees.talent(e.getKey()).totalCost(e.getValue())).sum();
    }
    ClassBuild rank(String id, int rank) {
        Map<String,Integer> next = new LinkedHashMap<>(ranks);
        if (rank == 0) next.remove(id); else next.put(id, rank);
        return new ClassBuild(classId, next, abilities);
    }
    ClassBuild equip(int slot, String id) {
        if (slot < 1 || slot > 2) throw new IllegalArgumentException("Номер слота: 1 или 2");
        List<String> next = new ArrayList<>(abilities);
        next.set(slot-1, id);
        return new ClassBuild(classId, ranks, next);
    }
}
