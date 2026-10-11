package ru.cherdakmd.chrdkenchants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Чистые правила выдачи: фильтрация применимых чар (поддержка предмета, лимиты, конфликты),
 * взвешенный выбор и уровень. Без Bukkit — тестируется константами.
 */
public final class EnchantRoller {

    private EnchantRoller() {
    }

    /**
     * Отбирает из {@code enabled} чары, которые можно ДОБАВИТЬ на предмет
     * ({@code materialName}) с уже наложенными {@code present} чарами при лимитах.
     * Ключи present — id чар текущего предмета (enabled = разрешённые к выпадению).
     */
    public static List<EnchantDef> applicable(List<EnchantDef> enabled, String materialName,
                                              Map<String, Integer> present,
                                              int maxPerItem, int maxLegendaryPerItem) {
        if (present.size() >= maxPerItem) {
            return List.of();
        }
        long legendary = present.keySet().stream()
                .map(EnchantRegistry::byId)
                .filter(def -> def != null && def.rarity() == EnchantDef.Rarity.LEGENDARY)
                .count();
        Set<String> groups = new HashSet<>();
        present.keySet().stream().map(EnchantRegistry::byId).forEach(def -> {
            if (def != null && def.conflictGroup() != null) {
                groups.add(def.conflictGroup());
            }
        });
        List<EnchantDef> result = new ArrayList<>();
        for (EnchantDef def : enabled) {
            if (present.containsKey(def.id())) continue; // уникальность; апгрейд уровней — отдельная механика, не трогаем
            if (def.conflictGroup() != null && groups.contains(def.conflictGroup())) continue;
            if (def.rarity() == EnchantDef.Rarity.LEGENDARY && legendary >= maxLegendaryPerItem) continue;
            if (!def.supports(materialName)) continue;
            result.add(def);
        }
        return result;
    }

    /** Взвешенный по редкости выбор (иначе пустой → null). */
    public static EnchantDef pickWeighted(List<EnchantDef> candidates, Random random) {
        if (candidates.isEmpty()) {
            return null;
        }
        int total = candidates.stream().mapToInt(def -> def.rarity().weight()).sum();
        int ticket = random.nextInt(Math.max(1, total));
        for (EnchantDef def : candidates) {
            ticket -= def.rarity().weight();
            if (ticket < 0) {
                return def;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * Уровень выданной чары: costTier (1..3 — стоимость лазурита/щедрость источника) и случайность.
     * На базовом уровне чаще I, на максимальной стойке чаще II–III (но редко максимум).
     */
    public static int rollLevel(EnchantDef def, int costTier, Random random) {
        int max = def.maxLevel();
        if (max <= 1) {
            return 1;
        }
        int level = 1;
        double bumpChance = switch (Math.max(1, Math.min(3, costTier))) {
            case 3 -> 0.40;
            case 2 -> 0.20;
            default -> 0.05;
        };
        while (level < max && random.nextDouble() < bumpChance) {
            level++;
        }
        return level;
    }
}
