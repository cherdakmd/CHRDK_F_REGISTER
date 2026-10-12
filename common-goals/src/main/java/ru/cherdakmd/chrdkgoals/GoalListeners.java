package ru.cherdakmd.chrdkgoals;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Слушатели игровых событий, фи́лящие ход общих целей. Все фильтры дешёвые:
 * метрика считается только если в волне есть активная цель соответствующего типа.
 */
public final class GoalListeners implements Listener {

    private final CommonGoalsPlugin plugin;

    public GoalListeners(CommonGoalsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            plugin.service().addProgress(GoalMetric.HOSTILE_SLAY, killer, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        plugin.service().addProgress(GoalMetric.BLOCKS_BROKEN, player, 1);
        if (MaterialGoals.isOre(event.getBlock().getType())) {
            plugin.service().addProgress(GoalMetric.ORE_BREAK, player, 1);
        }
        if (MaterialGoals.isCrop(event.getBlock().getType())) {
            plugin.service().addProgress(GoalMetric.CROPS_HARVESTED, player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.service().addProgress(GoalMetric.BLOCKS_PLACED, event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Entity caught = event.getCaught();
        if (caught != null) {
            plugin.service().addProgress(GoalMetric.FISH_CAUGHT, event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || event.getPlayer().isInsideVehicle()) {
            return;
        }
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL
                && event.getPlayer().getGameMode() != org.bukkit.GameMode.ADVENTURE) {
            return;
        }
        int distance = (int) Math.floor(event.getFrom().distance(event.getTo()));
        if (distance > 0 && distance < 50) {
            plugin.service().addProgress(GoalMetric.BLOCKS_TRAVELLED, event.getPlayer(), distance);
        }
    }
}
