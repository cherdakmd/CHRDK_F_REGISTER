package ru.cherdakmd.chrdkclaims;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Алмазная «мелочь» предметами: оценка, подсчёт, сбор и выдача. */
public final class DiamondItems {

    /** Эквивалент в алмазах: как в магазине/общих целях (руды = 1, блок = 9). */
    public static int valueOf(Material material) {
        return switch (material) {
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case DIAMOND_BLOCK -> 9;
            default -> 0;
        };
    }

    /** Алмазный эквивалент содержимого инвентаря (без брони/второй руки). */
    public static int count(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null) {
                total += valueOf(stack.getType()) * stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Изымает алмазные предметы на сумму amount из инвентаря. Единицы: целые предметы;
     * если добираем блоком (9 ⛁ ценой) — сдача возвращается числом, чтобы сервис
     * зачислил её в казну.
     *
     * @return сколько эквивалента изъято на самом деле (>= amount), 0 если не хватило
     */
    public static int collect(Inventory inventory, int amount) {
        if (amount <= 0 || count(inventory) < amount) {
            return 0;
        }
        // сначала «мелочь» (руда и алмазы по 1), блоки — в последнюю очередь
        int collected = collectMatching(inventory, amount, false);
        if (collected < amount) {
            collected += collectMatching(inventory, amount - collected, true);
        }
        return collected;
    }

    private static int collectMatching(Inventory inventory, int needed, boolean blocks) {
        int collected = 0;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && collected < needed; i++) {
            ItemStack stack = contents[i];
            if (stack == null) {
                continue;
            }
            int value = valueOf(stack.getType());
            if (value == 0 || (value == 9) != blocks) {
                continue;
            }
            int take = Math.min(stack.getAmount(), (needed - collected + value - 1) / value);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) {
                contents[i] = null;
            }
            collected += take * value;
        }
        inventory.setStorageContents(contents);
        return collected;
    }

    /** Выдаёт сумму предметами: блоками и алмазами; что не влезло — падает под ноги. */
    public static void give(Player player, int amount) {
        int remaining = amount;
        while (remaining >= 9) {
            int blocks = Math.min(64, remaining / 9);
            addOrDrop(player, new ItemStack(Material.DIAMOND_BLOCK, blocks));
            remaining -= blocks * 9;
        }
        if (remaining > 0) {
            addOrDrop(player, new ItemStack(Material.DIAMOND, remaining));
        }
    }

    private static void addOrDrop(Player player, ItemStack stack) {
        var leftover = player.getInventory().addItem(stack);
        leftover.values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private DiamondItems() {
    }
}
