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
        double multiplier = plugin.config().mobBossMultiplier();
        boolean boss = multiplier > 1.0 && event.getEntity().getCustomName() != null;
        int effectiveChance = Math.min(100, (int) Math.round(chance * (boss ? multiplier : 1.0)));
        boolean identify = plugin.config().identifyUnidentified();
        for (ItemStack drop : event.getDrops()) {
            if (drop == null) continue;
            ItemKind kind = ItemKind.classify(drop.getType().name());
            if (!plugin.config().kindAllowed(kind)) continue;
            if (plugin.config().materialBlocked(drop.getType())) continue;
            if (plugin.items().tierOf(drop) != null) continue;
            if (random.nextInt(100) >= effectiveChance) continue;
            if (identify) {
                // новый флоу: ярлык скрыт до инвентаря; игрок-ловец найдёт амбиции позже
                plugin.items().markUnidentified(drop, "drop",
                        plugin.messages().get("unidentified-name"),
                        java.util.List.of(plugin.messages().get("unidentified-lore")));
                continue;
            }
            TierDef.Snapshot roll = TierRoller.roll(plugin.config().tiers(), random);
            if (roll != null) {
                plugin.items().apply(drop, roll);
                // 1.4.81: находку засчитываем убийце (в identify-флоу — тому,
                // кто потом опознает предмет: тир до опознания ещё не известен).
                if (event.getEntity().getKiller() instanceof org.bukkit.entity.Player killer) {
                    plugin.stats().count(killer.getUniqueId(), roll.id());
                }
            }
        }
    }
}
