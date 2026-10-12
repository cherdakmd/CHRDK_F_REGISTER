package ru.cherdakmd.chrdktierforge;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Мировая добыча: экипировка (броня/оружие/инструменты), выпавшая с мертвецов,
 * с шансом приходит уже с накатом редкости. Тир-предметы добываются не только
 * крафтом — зачищенные зомби и скелеты тоже лотерея.
 */
public final class TierMobDropListener implements Listener {

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    public TierMobDropListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.config().mobDropEnabled()) return;
        int chance = plugin.config().mobDropChancePercent();
        if (chance <= 0) return;
        for (ItemStack drop : event.getDrops()) {
            if (drop == null) continue;
            ItemKind kind = ItemKind.classify(drop.getType().name());
            if (!plugin.config().kindAllowed(kind)) continue;
            if (plugin.items().tierOf(drop) != null) continue;
            if (random.nextInt(100) >= chance) continue;
            TierDef.Snapshot roll = TierRoller.roll(plugin.config().tiers(), random);
            if (roll != null) {
                plugin.items().apply(drop, roll);
            }
        }
    }
}
