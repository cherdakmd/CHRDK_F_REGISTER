package ru.cherdakmd.chrdktierforge;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Наковальня: два тир-предмета одного материала и одного тира сливаются
 * в предмет тиром выше (максимум — Легендарный). Цена — опыт, растёт с индексом тира.
 */
public final class TierAnvilListener implements Listener {

    private final TierForgePlugin plugin;

    public TierAnvilListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!plugin.config().anvilEnabled()) return;
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || right == null) return;
        Player player = event.getView().getPlayer() instanceof Player p ? p : null;
        if (player == null) return;

        String leftTier = plugin.items().tierOf(left);
        String rightTier = plugin.items().tierOf(right);
        if (leftTier == null && rightTier == null) return; // не наша пара
        if (leftTier == null || rightTier == null
                || !leftTier.equals(rightTier)
                || left.getType() != right.getType()) {
            plugin.messages().send(player, "up-mismatch");
            return;
        }
        String next = TierDef.next(leftTier);
        if (next == null) {
            plugin.messages().send(player, "up-max");
            return;
        }
        TierDef.Snapshot target = plugin.config().find(next);
        if (target == null) return;

        ItemStack result = left.clone();
        plugin.items().apply(result, target);
        event.setResult(result);
        event.getInventory().setRepairCost(
                TierMath.upgradeXpCost(plugin.config().anvilBaseXp(), TierDef.indexOf(next)));
        plugin.messages().send(player, "upgraded",
                "tier", plugin.messages().raw("tier." + next + ".name"));
    }
}
