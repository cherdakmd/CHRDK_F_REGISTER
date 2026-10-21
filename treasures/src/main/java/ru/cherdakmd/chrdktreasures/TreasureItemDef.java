package ru.cherdakmd.chrdktreasures;

import java.util.List;
import java.util.Locale;

/**
 * Именной предмет из клада.
 *
 * <p>Материал хранится строкой, а не {@code Material}: каталог остаётся чистым
 * и проверяется без Bukkit, а неизвестное имя ловится при сборке предмета и в
 * тесте каталога.</p>
 *
 * @param id       ид для messages.yml ({@code treasure.<ид>.name|desc}) и PDC
 * @param tier     редкость
 * @param region   регион; {@code null} только у мифических (общий пул)
 * @param material имя материала Bukkit
 * @param effects  эффекты предмета
 * @param weight   вес в таблице своей редкости (для будущих переопределений)
 */
public record TreasureItemDef(String id, TreasureTier tier, TreasureRegion region,
                              String material, List<TreasureEffect> effects, int weight) {

    public TreasureItemDef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("у предмета должен быть ид");
        }
        if (tier == null) {
            throw new IllegalArgumentException("у предмета нет редкости: " + id);
        }
        if (tier.regional() && region == null) {
            throw new IllegalArgumentException("региональный предмет без региона: " + id);
        }
        if (tier == TreasureTier.MYTHICAL && region != null) {
            throw new IllegalArgumentException("мифический предмет не привязан к биому: " + id);
        }
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("у предмета нет материала: " + id);
        }
        effects = List.copyOf(effects == null ? List.of() : effects);
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("у предмета нет эффектов: " + id);
        }
        if (weight < 1) {
            throw new IllegalArgumentException("вес предмета меньше единицы: " + id);
        }
    }

    /** Есть ли у предмета эффект этого вида. */
    public boolean has(TreasureEffect.Kind kind) {
        for (TreasureEffect effect : effects) {
            if (effect.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** Суммарная величина эффекта этого вида. */
    public double value(TreasureEffect.Kind kind) {
        double total = 0.0;
        for (TreasureEffect effect : effects) {
            if (effect.kind() == kind) {
                total += effect.value();
            }
        }
        return total;
    }

    /** Броня ли это (по имени материала). */
    public boolean armor() {
        String name = material.toUpperCase(Locale.ROOT);
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }
}
