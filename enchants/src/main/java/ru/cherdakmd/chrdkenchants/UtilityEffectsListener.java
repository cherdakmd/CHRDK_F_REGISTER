package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Утилиты: только свой экипированный предмет, штатные события и ограниченные контексты. */
final class UtilityEffectsListener implements Listener {
    private static final int LIMIT = 4096;
    private record Wear(BlockBreakEvent source, int tick, ItemStack tool) { }
    private final EnchantsPlugin plugin;
    private final NamespacedKey workReady, airReady;
    private final Map<UUID, Wear> work = new HashMap<>();
    private final Map<UUID, UUID> sessions = new HashMap<>();
    private final Map<UUID, UUID> airPending = new HashMap<>();
    private final BukkitTask cleanup;

    UtilityEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
        workReady = new NamespacedKey(plugin, "utility_work_ready");
        airReady = new NamespacedKey(plugin, "utility_air_ready");
        cleanup = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int tick = Bukkit.getCurrentTick();
            work.entrySet().removeIf(entry -> entry.getValue().tick != tick);
        }, 20L, 20L);
    }
    void reload() { work.clear(); sessions.clear(); airPending.clear(); }
    void stop() { cleanup.cancel(); reload(); }
    private boolean eligible(Player player) {
        return plugin.getConfig().getBoolean("utility-effects.enabled", true) && player.isOnline() && !player.isDead()
                && player.getGameMode() == GameMode.SURVIVAL && player.hasPermission("chrdkenchants.use")
                && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    private int level(ItemStack item, UtilityEnchant enchant) {
        if (item == null || !plugin.config().isEnabled(enchant.id)
                || !EnchantRegistry.byId(enchant.id).supports(item.getType().name())) return 0;
        int level = plugin.items().read(item).getOrDefault(enchant.id, 0);
        return level <= 0 ? 0 : enchant.level(level);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void block(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player) || work.size() >= LIMIT) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (level(tool, UtilityEnchant.CAREFUL_WORK) == 0) return;
        work.put(player.getUniqueId(), new Wear(event, Bukkit.getCurrentTick(), tool.clone()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void wear(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        Wear context = work.remove(player.getUniqueId());
        if (context == null || !eligible(player) || context.source.isCancelled() || context.tick != Bukkit.getCurrentTick()
                || event.getDamage() <= 0 || !PveEquipment.same(context.tool, event.getItem())
                || !PveEquipment.same(context.tool, player.getInventory().getItemInMainHand())) return;
        int level = level(event.getItem(), UtilityEnchant.CAREFUL_WORK);
        long now = System.currentTimeMillis();
        if (level == 0 || player.getPersistentDataContainer().getOrDefault(workReady, PersistentDataType.LONG, 0L) > now
                || plugin.service().random().nextInt(100) >= UtilityEnchant.CAREFUL_WORK.chance(level)) return;
        event.setDamage(Math.max(0, event.getDamage() - 1));
        // Таймер фиксируется сразу: даже поздняя отмена износа не даёт повторного броска/обхода релогом.
        player.getPersistentDataContainer().set(workReady, PersistentDataType.LONG, now + UtilityEnchant.CAREFUL_WORK.cooldownMillis());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void garden(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND && eligible(event.getPlayer())
                && level(event.getPlayer().getInventory().getBoots(), UtilityEnchant.GARDEN_STEP) > 0)
            event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void step(GenericGameEvent event) {
        if (event.getEvent() == GameEvent.STEP && event.getEntity() instanceof Player player && eligible(player)
                && !player.isSprinting() && !player.isGliding() && !player.isFlying()
                && level(player.getInventory().getBoots(), UtilityEnchant.QUIET_STEP) > 0)
            event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void air(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || !eligible(player) || airPending.size() >= LIMIT
                || airPending.containsKey(player.getUniqueId()) || player.getRemainingAir() <= 40 || event.getAmount() > 40
                || event.getAmount() >= player.getRemainingAir()) return;
        ItemStack helmet = player.getInventory().getHelmet();
        int level = level(helmet, UtilityEnchant.AIR_RESERVE);
        if (level == 0 || player.getPersistentDataContainer().getOrDefault(airReady, PersistentDataType.LONG, 0L) > System.currentTimeMillis()) return;
        ItemStack expected = helmet.clone();
        UUID id = player.getUniqueId(), world = player.getWorld().getUID();
        UUID session = sessions.computeIfAbsent(id, ignored -> UUID.randomUUID());
        UUID token = UUID.randomUUID();
        airPending.put(id, token);
        long generation = plugin.sourceGeneration();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!token.equals(airPending.get(id))) return;
            airPending.remove(id);
            if (!session.equals(sessions.get(id)) || generation != plugin.sourceGeneration() || event.isCancelled()
                    || event.getAmount() > 40 || !eligible(player) || !world.equals(player.getWorld().getUID())
                    || !PveEquipment.same(expected, player.getInventory().getHelmet())
                    || level(player.getInventory().getHelmet(), UtilityEnchant.AIR_RESERVE) != level
                    || player.getEyeLocation().getBlock().getType() != Material.WATER || player.getRemainingAir() > 40) return;
            long now = System.currentTimeMillis();
            if (player.getPersistentDataContainer().getOrDefault(airReady, PersistentDataType.LONG, 0L) > now) return;
            int before = player.getRemainingAir();
            int amount = Math.min(player.getMaximumAir(), Math.max(0, before) + UtilityEnchant.AIR_RESERVE.air(level));
            if (amount <= before) return;
            player.setRemainingAir(amount);
            if (player.getRemainingAir() <= before) return;
            player.getPersistentDataContainer().set(airReady, PersistentDataType.LONG, now + UtilityEnchant.AIR_RESERVE.cooldownMillis());
        });
    }
    private void forget(Player player) {
        UUID id = player.getUniqueId(); work.remove(id); sessions.remove(id); airPending.remove(id);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { forget(event.getEntity()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) { forget(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void mode(PlayerGameModeChangeEvent event) { forget(event.getPlayer()); }
}
