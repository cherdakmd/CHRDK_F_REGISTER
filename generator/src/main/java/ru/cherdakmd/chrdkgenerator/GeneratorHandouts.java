package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Выдачи игроку: бункер — предметами по таблице тира, излишек под ноги
 * (принцип выдачи из daily-rewards), ядро — предметом с прежним
 * идентификатором. Общий для снятия ядра, перерождения и меню.
 */
final class GeneratorHandouts {

    private GeneratorHandouts() {
    }

    /** Бункер — предметами текущего тира; чем не влезло — дропом под ноги. */
    static void giveBunker(Player player, GeneratorConfig config, GeneratorStore.Entry before,
                           long units, Random random) {
        if (units <= 0) {
            return;
        }
        GeneratorConfig.Tier tier = config.tier(before.tier());
        int luck = before.modules().getOrDefault("luck", 0);
        Map<String, Integer> items = GeneratorDrops.materialize(units, tier.drops(), luck, random);
        for (var row : items.entrySet()) {
            Material material = Material.matchMaterial(row.getKey());
            if (material == null || material.isAir()) {
                continue;
            }
            int remaining = row.getValue();
            while (remaining > 0) {
                int take = Math.min(remaining, material.getMaxStackSize());
                var leftover = player.getInventory().addItem(new ItemStack(material, take));
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
                remaining -= take;
            }
        }
    }

    /** Ядро возвращается предметом с ТЕМ же идентификатором (бинд снят). */
    static void giveCore(Player player, GeneratorCoreItem coreItem, UUID coreId) {
        if (coreId == null) {
            return;
        }
        var leftover = player.getInventory().addItem(coreItem.build(coreId));
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }
}
