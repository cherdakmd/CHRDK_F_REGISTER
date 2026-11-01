package ru.cherdakmd.chrdktierforge;

import java.util.List;

/** Применение сохранённых свойств не выдаёт новые и не стирает отключённые из PDC. */
final class TierPropertySelection {
    private TierPropertySelection() { }

    static List<TierProperty> active(List<TierProperty> stored, ItemKind kind, String tier,
                                     boolean enabled, int limit) {
        if (!enabled || kind == null || tier == null || limit <= 0) return List.of();
        return stored.stream().filter(property -> property.tier().equals(tier) && property.fits(kind))
                .distinct().limit(Math.min(TierProperties.MAX_PER_ITEM, limit)).toList();
    }
}
