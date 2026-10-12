package ru.cherdakmd.chrdkenchants;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Источник: зачаровательный стол. При ванильном зачаровании с шансом
 * (масштабируется ценой 1..3 лазурита) добавляется одна кастомная чара.
 */
public final class TableListener implements Listener {

    private final EnchantsPlugin plugin;

    public TableListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (!plugin.config().tableEnabled()) return;
        Player player = event.getEnchanter();
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        int tier = event.whichButton() + 1; // 1..3
        double base = plugin.config().tableChancePercent() / 100.0;
        double chance = base * tier / 3.0;
        if (plugin.service().random().nextDouble() >= chance) return;

        ItemStack item = event.getItem();
        EnchantService.RollResult roll = plugin.service().rollOnto(item, tier);
        if (roll == null) return;
        plugin.messages().send(player, "table-added",
                "name", plugin.messages().raw("en." + roll.def().id() + ".name"),
                "levelroman", "I");
        // маленький колдовской флёр: убираем известную гибель удачи
        try {
            player.spawnParticle(org.bukkit.Particle.WITCH, player.getLocation().add(0, 1.2, 0),
                    18, 0.5, 0.6, 0.5, 0.02);
        } catch (Throwable ignored) {
            // недоступная частица не губит зачарование
        }
    }
}
