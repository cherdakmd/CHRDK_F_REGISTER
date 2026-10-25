package ru.cherdakmd.chrdkgoals;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Слушатели игровых событий, фи́лящие ход общих целей. Все фильтры дешёвые:
 * метрика считается только если в волне есть активная цель соответствующего типа
 * (проверка внутри service.addProgress — по метрике активных целей волны).
 */
public final class GoalListeners implements Listener {

    /** Боссы, засчитываемые цели «бич гигантов». */
    private static final Set<EntityType> BOSSES = Set.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER,
            EntityType.WARDEN, EntityType.ELDER_GUARDIAN
    );

    private final CommonGoalsPlugin plugin;

    public GoalListeners(CommonGoalsPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------- бой --

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        if (event.getEntity() instanceof Monster) {
            plugin.service().addProgress(GoalMetric.HOSTILE_SLAY, killer, 1);
        }
        if (event.getEntity() instanceof Animals) {
            plugin.service().addProgress(GoalMetric.PASSIVE_SLAY, killer, 1);
        }
        if (BOSSES.contains(event.getEntityType())) {
            plugin.service().addProgress(GoalMetric.BOSS_SLAY, killer, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.service().addProgress(GoalMetric.BOW_SHOOT, player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl
                && event.getEntity().getShooter() instanceof Player player) {
            plugin.service().addProgress(GoalMetric.PEARL_THROW, player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidFinish(RaidFinishEvent event) {
        for (Player winner : event.getWinners()) {
            plugin.service().addProgress(GoalMetric.RAID_WIN, winner, 1);
        }
    }

    // ------------------------------------------------------ шахтёрство --

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
        if (MaterialGoals.isLog(event.getBlock().getType())) {
            plugin.service().addProgress(GoalMetric.WOOD_CHOP, player, 1);
        }
        if (MaterialGoals.isDiggable(event.getBlock().getType())) {
            plugin.service().addProgress(GoalMetric.GROUND_DIG, player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        plugin.service().addProgress(GoalMetric.BLOCKS_PLACED, event.getPlayer(), 1);
    }

    // ------------------------------------------------------------ ферма --

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
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            plugin.service().addProgress(GoalMetric.ANIMAL_BREED, player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            plugin.service().addProgress(GoalMetric.ANIMAL_TAME, player, 1);
        }
    }

    // ------------------------------------------------------ магия/ремесло --

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        plugin.service().addProgress(GoalMetric.ENCHANT_ITEMS, event.getEnchanter(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent event) {
        // зелья варятся без участника: засчитываем зрителям стойки (открывшим её игрокам)
        int brewed = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack potion = event.getContents().getItem(slot);
            if (potion != null && !potion.getType().isAir()) {
                brewed++;
            }
        }
        if (brewed == 0) {
            return;
        }
        for (org.bukkit.entity.HumanEntity viewer : event.getContents().getViewers()) {
            if (viewer instanceof Player player) {
                plugin.service().addProgress(GoalMetric.POTION_BREW, player, brewed);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) {
            return;
        }
        ItemStack[] matrix = event.getInventory().getMatrix();
        int[] amounts = new int[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            amounts[i] = matrix[i] == null ? 0 : matrix[i].getAmount();
        }
        int crafted = CraftCounter.craftedAmount(event.isShiftClick(), result.getAmount(), amounts);
        if (crafted > 0) {
            plugin.service().addProgress(GoalMetric.ITEM_CRAFT, player, crafted);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        plugin.service().addProgress(GoalMetric.ITEM_SMELT, event.getPlayer(), event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrade(PlayerTradeEvent event) {
        plugin.service().addProgress(GoalMetric.VILLAGER_TRADE, event.getPlayer(), 1);
    }

    // ------------------------------------------------------------ мир/быт --

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(PlayerExpChangeEvent event) {
        if (event.getAmount() > 0) {
            plugin.service().addProgress(GoalMetric.XP_COLLECT, event.getPlayer(), event.getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            plugin.service().addProgress(GoalMetric.BED_SLEEP, event.getPlayer(), 1);
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
