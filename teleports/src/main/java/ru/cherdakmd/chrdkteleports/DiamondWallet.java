package ru.cherdakmd.chrdkteleports;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Учёт и списание обычных алмазов из инвентаря игрока (стеки DIAMOND, слоты хранения).
 * Только серверные классы — в юнит-тестах не используется.
 */
public final class DiamondWallet {

    private DiamondWallet() {
    }

    public static int count(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.DIAMOND) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Списывает ровно {@code amount} алмазов. true = успешно; при недостатке ничего не меняется.
     */
    public static boolean charge(Player player, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (count(player) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != Material.DIAMOND) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            if (take >= stack.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            remaining -= take;
        }
        return remaining == 0;
    }
}
