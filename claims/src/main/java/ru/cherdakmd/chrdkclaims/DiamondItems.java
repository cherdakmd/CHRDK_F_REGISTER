package ru.cherdakmd.chrdkclaims;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;


/** Алмазная «мелочь» предметами: только оценка и подсчёт; мутации выполняет canonical Core. */
public final class DiamondItems {

    /** Эквивалент в алмазах: как в магазине/общих целях (руды = 1, блок = 9). */
    public static int valueOf(Material material) {
        return switch (material) {
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case DIAMOND_BLOCK -> 9;
            default -> 0;
        };
    }

    static boolean plain(ItemStack item) {
        return item != null && !item.getType().isAir() && !item.hasItemMeta() && item.getAmount() > 0 && item.getAmount() <= 64;
    }
    /** Алмазный эквивалент содержимого инвентаря (без брони/второй руки). */
    public static int count(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (plain(stack)) {
                total += valueOf(stack.getType()) * stack.getAmount();
            }
        }
        return total;
    }

    private DiamondItems() {
    }
}
