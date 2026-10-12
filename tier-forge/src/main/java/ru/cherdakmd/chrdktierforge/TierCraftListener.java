package ru.cherdakmd.chrdktierforge;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Накат редкости при крафте (и сохранение тира при апгрейде на кузнечном столе).
 */
public final class TierCraftListener implements Listener {

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    public TierCraftListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null) return;
        Player player = event.getView().getPlayer() instanceof Player p ? p : null;
        if (player == null) return;
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        ItemKind kind = ItemKind.classify(result.getType().name());
        if (!plugin.config().kindAllowed(kind)) {
            return;
        }
        // не пересматриваем чужие тир-предметы
        if (plugin.items().tierOf(result) != null) return;

        int chance = plugin.config().rollOnCraftChancePercent();
        if (chance <= 0 || random.nextInt(100) >= chance) return;
        TierDef.Snapshot roll = TierRoller.roll(plugin.config().tiers(), random);
        if (roll == null) return;
        plugin.items().apply(result, roll);
        if ("cursed".equals(roll.id())) {
            plugin.messages().send(player, "rolled-cursed");
        } else if (roll.percent() != 0) {
            plugin.messages().send(player, "rolled",
                    "tier", plugin.messages().raw("tier." + roll.id() + ".name"));
        }
    }

    /** Кузнечный стол: апгрейд в незерит сохраняет накатанный тир. */
    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        ItemStack base = event.getInventory().getInputEquipment();
        if (base == null) return;
        String tier = plugin.items().tierOf(base);
        if (tier == null) return;
        TierDef.Snapshot snapshot = plugin.config().find(tier);
        if (snapshot == null) return;
        plugin.items().apply(result, snapshot);
    }
}
