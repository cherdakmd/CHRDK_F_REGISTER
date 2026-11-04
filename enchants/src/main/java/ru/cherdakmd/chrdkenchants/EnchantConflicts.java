package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Единые правила несовместимости кастомных чар (1.4.282).
 *
 * <p>Реестр задаёт {@link EnchantDef#conflictGroup()}: две разные чары одной
 * группы не могут быть на одном предмете. Раньше это правило проверялось только
 * в {@link EnchantRoller} и {@link EnchantService#canApplyFromBook}, поэтому
 * перенос чар через публичный API, чтение готовой PDC и мастерская могли
 * получить предмет с «Жилодробителем» и «Дробителем» одновременно — а
 * {@code ToolEffectsListener} запускал обе ветки добычи за один удар.</p>
 *
 * <p>Класс чистый (без Bukkit): его можно звать и из горячего пути слушателя,
 * и из юнит-тестов. Неизвестные id считаются безопасными, чтобы старые предметы
 * с чужой PDC не превращались в пустышку.</p>
 */
public final class EnchantConflicts {

    private EnchantConflicts() {
    }

    /** Группа чары или {@code null} (нет группы / неизвестный id). */
    public static String group(String id) {
        if (id == null) {
            return null;
        }
        EnchantDef def = EnchantRegistry.byId(id);
        return def == null ? null : def.conflictGroup();
    }

    /** Две разные чары одной группы. Одна и та же чара конфликтом не считается. */
    public static boolean sameGroup(String left, String right) {
        if (left == null || right == null || left.equals(right)) {
            return false;
        }
        String group = group(left);
        return group != null && group.equals(group(right));
    }

    /** Нельзя ли добавить {@code id} к уже стоящему набору. */
    public static boolean conflictsWith(Map<String, Integer> present, String id) {
        if (present == null || present.isEmpty()) {
            return false;
        }
        String group = group(id);
        if (group == null) {
            return false;
        }
        for (String other : present.keySet()) {
            if (!other.equals(id) && group.equals(group(other))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Целостность набора: ни одной пары чар из одной группы.
     * Пустой набор и неизвестные id валидны.
     */
    public static boolean validSet(Map<String, Integer> enchants) {
        if (enchants == null || enchants.size() < 2) {
            return true;
        }
        List<String> ids = new ArrayList<>(enchants.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (sameGroup(ids.get(i), ids.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Безопасное объединение: чары из {@code source} переносятся на {@code target},
     * но та, что конфликтует с уже стоящей (или с earlier перенесённой), отбрасывается.
     * Одинаковые id сливаются по максимуму уровня, как в наковальне.
     */
    public static Map<String, Integer> merge(Map<String, Integer> source, Map<String, Integer> target) {
        Map<String, Integer> result = new LinkedHashMap<>(target == null ? Map.of() : target);
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String id = entry.getKey();
            if (id == null || entry.getValue() == null) {
                continue;
            }
            if (result.containsKey(id)) {
                result.merge(id, entry.getValue(), Math::max);
            } else if (!conflictsWith(result, id)) {
                result.put(id, entry.getValue());
            }
        }
        return result;
    }
}
