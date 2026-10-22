package ru.cherdakmd.chrdktierforge;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
        LivingEntity entity = event.getEntity();
        // Стойки и (на всякий случай) игроки не «мертвецы» для этой лотереи (1.4.83).
        if (entity instanceof ArmorStand || entity instanceof Player) return;
        if (plugin.config().mobWorldBlocked(entity.getWorld().getName())) return;
        if (plugin.config().mobPlayerKillOnly() && entity.getKiller() == null) return;
        double multiplier = plugin.config().mobBossMultiplier();
        // 1.4.83: настоящие боссы (визер/дракон) считаются боссами и без имени;
        // именной моб остаётся «мини-боссом» как и раньше.
        boolean boss = multiplier > 1.0
                && (entity instanceof Boss
                || (entity.getCustomName() != null && !entity.getCustomName().isBlank()));
        boolean identify = plugin.config().identifyUnidentified();
        for (ItemStack drop : event.getDrops()) {
            if (drop == null) continue;
            ItemKind kind = ItemKind.classify(drop.getType().name());
            if (!plugin.config().kindAllowed(kind)) continue;
            if (plugin.config().materialBlocked(drop.getType())) continue;
            if (plugin.items().tierOf(drop) != null) continue;
            // 1.4.87: шанс считается для КАЖДОГО дропа по его виду, а не один общий
            // на весь труп — оружие, броню и инструменты можно настроить отдельно.
            int chance = plugin.config().mobDropChanceFor(kind);
            if (chance <= 0) continue;
            int effectiveChance = Math.min(100,
                    (int) Math.round(chance * (boss ? multiplier : 1.0)));
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
                if (entity.getKiller() instanceof Player killer) {
                    plugin.stats().count(killer.getUniqueId(), roll.id());
                    plugin.collection().record(killer, drop);
                }
            }
        }
    }
}
