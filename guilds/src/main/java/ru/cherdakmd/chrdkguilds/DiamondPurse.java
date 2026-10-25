package ru.cherdakmd.chrdkguilds;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Казна в алмазах (1.4.163).
 *
 * <p>Экономика сервера — предметы, а не число в базе: алмазный блок стоит
 * девять алмазов, руда — один. Гильдия принимает взносы теми же предметами,
 * что игрок носит в инвентаре, поэтому казна не печатает валюту из воздуха и
 * не зависит от банка ядра.</p>
 *
 * <p>Взносы сначала списывают «мелочь», а блоки — в последнюю очередь: игрок не
 * должен терять блок из-за того, что у него нашлось девять алмазов.</p>
 */
final class DiamondPurse implements GuildPurse {

    /** Эквивалент предмета в алмазах: как в магазине и общих целях. */
    static int valueOf(Material material) {
        return switch (material) {
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case DIAMOND_BLOCK -> 9;
            default -> 0;
        };
    }

    /** Алмазный эквивалент инвентаря (без брони и второй руки). */
    static long count(Inventory inventory) {
        long total = 0;
        if (inventory == null) {
            return 0;
        }
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null) {
                total += (long) valueOf(stack.getType()) * stack.getAmount();
            }
        }
        return total;
    }

    @Override
    public long take(UUID player, long amount) {
        if (amount <= 0) {
            return 0L;
        }
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return 0L;
        }
        Inventory inventory = online.getInventory();
        if (count(inventory) < amount) {
            return 0L;
        }
        long collected = collectMatching(inventory, amount, false);
        if (collected < amount) {
            collected += collectMatching(inventory, amount - collected, true);
        }
        return collected;
    }

    private static long collectMatching(Inventory inventory, long needed, boolean blocks) {
        long collected = 0;
        ItemStack[] contents = inventory.getStorageContents();
        for (int index = 0; index < contents.length && collected < needed; index++) {
            ItemStack stack = contents[index];
            if (stack == null) {
                continue;
            }
            int value = valueOf(stack.getType());
            if (value == 0 || (value == 9) != blocks) {
                continue;
            }
            int take = (int) Math.min(stack.getAmount(), (needed - collected + value - 1) / value);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) {
                contents[index] = null;
            }
            collected += (long) take * value;
        }
        inventory.setStorageContents(contents);
        return collected;
    }

    @Override
    public long give(UUID player, long amount) {
        if (amount <= 0) {
            return 0L;
        }
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return amount;
        }
        long remaining = amount;
        long dropped = 0;
        while (remaining >= 9) {
            int blocks = (int) Math.min(64, remaining / 9);
            dropped += addOrDrop(online, new ItemStack(Material.DIAMOND_BLOCK, blocks));
            remaining -= (long) blocks * 9;
        }
        if (remaining > 0) {
            dropped += addOrDrop(online, new ItemStack(Material.DIAMOND, (int) remaining));
        }
        return dropped;
    }

    private static long addOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        long dropped = 0;
        for (ItemStack rest : leftover.values()) {
            dropped += (long) valueOf(rest.getType()) * rest.getAmount();
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return dropped;
    }
}
