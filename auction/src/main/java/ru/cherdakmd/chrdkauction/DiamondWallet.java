package ru.cherdakmd.chrdkauction;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Plain vanilla diamonds only. Custom named/enchanted diamonds are never spent or overwritten. */
public final class DiamondWallet {
    private static final ItemStack DIAMOND = new ItemStack(Material.DIAMOND);
    private DiamondWallet() { }
    static boolean isCurrency(ItemStack stack) { return stack != null && stack.isSimilar(DIAMOND); }
    public static int count(Player p) {
        int count = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) if (isCurrency(item)) count += item.getAmount();
        return count;
    }
    public static boolean charge(Player p, int amount) {
        if (amount <= 0 || count(p) < amount) return false;
        ItemStack[] items = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < items.length && left > 0; i++) {
            if (!isCurrency(items[i])) continue;
            int take = Math.min(left, items[i].getAmount());
            int rest = items[i].getAmount() - take;
            if (rest == 0) items[i] = null;
            else { items[i] = items[i].clone(); items[i].setAmount(rest); }
            left -= take;
        }
        p.getInventory().setStorageContents(items);
        return true;
    }
    public static int capacity(Player p) {
        int capacity = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) capacity += 64;
            else if (isCurrency(item)) capacity += Math.max(0, 64 - item.getAmount());
        }
        return capacity;
    }
    /** Amount must be <= capacity, checked in the same main-thread operation. Never drop prizes. */
    public static void give(Player p, int amount) {
        if (amount < 0 || amount > capacity(p)) throw new IllegalArgumentException("inventory capacity");
        ItemStack[] items = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < items.length && left > 0; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) {
                int take = Math.min(left, 64);
                items[i] = new ItemStack(Material.DIAMOND, take); left -= take;
            } else if (isCurrency(item)) {
                int take = Math.min(left, Math.max(0, 64 - item.getAmount()));
                items[i] = item.clone(); items[i].setAmount(item.getAmount() + take); left -= take;
            }
        }
        p.getInventory().setStorageContents(items);
    }
}
