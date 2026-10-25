package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Material;

/**
 * Паспорт артефакта из статического каталога.
 *
 * @param id              аски-идентификатор (ключ в PDC и конфиге)
 * @param side            светлая или проклятая сторона
 * @param mode            пассивный (работает из инвентаря) или активный (ПКМ)
 * @param material        базовый материал-представитель (не съедобный/не метательный)
 * @param cooldownSeconds дефолтный кулдаун активации (только активные)
 * @param xpCostLevels    дефолтная цена активации в уровнях опыта (только активные)
 */
public record ArtifactDef(String id, Side side, Mode mode, Material material,
                          int cooldownSeconds, int xpCostLevels) {

    public enum Side {GOOD, CURSED}

    public enum Mode {PASSIVE, ACTIVE}

    public ArtifactDef(String id, Side side, Mode mode, Material material) {
        this(id, side, mode, material, 0, 0);
    }
}
