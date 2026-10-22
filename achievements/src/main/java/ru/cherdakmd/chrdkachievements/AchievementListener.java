package ru.cherdakmd.chrdkachievements;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Событийный учёт достижений.
 *
 * <p>«Честная игра»: блоки, мобы и дистанция не считаются в креативе и
 * спектаторе, а полёт по умолчанию не даёт километров (переключается в конфиге
 * {@code achievements.count-flying}). Дистанция копится в буфере и сбрасывается
 * раз в секунду: каждое движение игрока писать в хранилище нельзя.</p>
 *
 * <p>Боссы — дракон Края, Визер и Варден: они идут и в общий счётчик мобов, и в
 * отдельный «боссы». Убийство игрока мобом сюда не относится: нужен
 * {@code getKiller()} — игрок.</p>
 */
public final class AchievementListener implements Listener {

    /** Боссы, которых считаем отдельно. */
    static final Set<EntityType> BOSSES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);

    /** Руды, идущие в счётчик «ores-mined». */
    static final Set<Material> ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS);

    private final AchievementsPlugin plugin;
    private final AchievementService service;
    /** Накопленная дистанция до следующего сброса: uuid → блоки. */
    private final Map<UUID, Double> pendingDistance = new ConcurrentHashMap<>();
    /** Кто открыл варочную стойку: позиция → uuid (BrewEvent не знает игрока). */
    private final Map<org.bukkit.Location, UUID> brewingStandOwners = new ConcurrentHashMap<>();

    public AchievementListener(AchievementsPlugin plugin, AchievementService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.addProgress(player, AchievementMetric.JOINS, 1L);
        service.refreshBadge(player);
        checkSocial(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        flushDistance(event.getPlayer());
        service.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!honest(event.getPlayer()) || !plugin.config().countFlying()
                && event.getPlayer().isFlying()) {
            return;
        }
        if (event.getFrom().getWorld() == null || event.getTo() == null
                || event.getFrom().getWorld() != event.getTo().getWorld()) {
            // Смена мира/телепорт: расстояние между мирами не считается.
            return;
        }
        double distance = event.getFrom().distance(event.getTo());
        if (distance <= 0.0 || distance > 64.0) {
            // Телепорт, рывок артефакта и лаг-скачок не должны давать километры.
            return;
        }
        pendingDistance.merge(event.getPlayer().getUniqueId(), distance, Double::sum);
    }

    /** Пульс: раз в секунду сбрасывает накопленную дистанцию. */
    public void flushDistance(Player player) {
        if (player == null) {
            return;
        }
        Double distance = pendingDistance.remove(player.getUniqueId());
        if (distance == null || distance < 1.0) {
            return;
        }
        if (!honest(player)) {
            return;
        }
        service.addProgress(player, AchievementMetric.DISTANCE_BLOCKS, (long) Math.floor(distance));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!honest(player)) {
            return;
        }
        service.addProgress(player, AchievementMetric.BLOCKS_MINED, 1L);
        if (ORES.contains(event.getBlock().getType())) {
            service.addProgress(player, AchievementMetric.ORES_MINED, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !honest(killer) || event.getEntity() instanceof Player) {
            return;
        }
        if (BOSSES.contains(event.getEntityType())) {
            service.addProgress(killer, AchievementMetric.BOSSES_KILLED, 1L);
        }
        service.addProgress(killer, AchievementMetric.MOBS_KILLED, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item)) {
            return;
        }
        Player player = event.getPlayer();
        if (honest(player)) {
            service.addProgress(player, AchievementMetric.FISH_CAUGHT, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !honest(player)) {
            return;
        }
        int amount = event.getRecipe().getResult().getAmount();
        // Shift-клик крафтит столько, сколько влезает: результат рецепта — не
        // фактическое количество, поэтому берём минимум из результата и стека.
        amount = Math.max(1, Math.min(64, amount));
        service.addProgress(player, AchievementMetric.ITEMS_CRAFTED, amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player == null || !honestAsync(player)) {
            return;
        }
        service.addProgress(player, AchievementMetric.CHAT_MESSAGES, 1L);
    }

    /** Опрос социальных достижений через API ядра. */
    public void checkSocial(Player player) {
        if (player == null || plugin.coreApi() == null) {
            return;
        }
        try {
            if (plugin.coreApi().hasActivePass(player.getName())) {
                service.setFlag(player, AchievementMetric.PASS_ACTIVE);
            }
            if (plugin.coreApi().isRegistered(player.getName())) {
                service.setFlag(player, AchievementMetric.REGISTERED);
            }
            if (plugin.coreApi().isTwoFactorEnabled(player.getName())) {
                service.setFlag(player, AchievementMetric.TWO_FACTOR);
            }
            // Банк (API v7): пиковый баланс и суммы переводов из ядра.
            long balance = plugin.coreApi().bankBalance(player.getName());
            if (balance > 0L) {
                service.raise(player, AchievementMetric.BANK_BALANCE, balance);
            }
            long sent = plugin.coreApi().bankSent(player.getName());
            if (sent > 0L) {
                service.raise(player, AchievementMetric.BANK_SENT, sent);
            }
            long received = plugin.coreApi().bankReceived(player.getName());
            if (received > 0L) {
                service.raise(player, AchievementMetric.BANK_RECEIVED, received);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Не удалось опросить ядро для достижений: "
                    + exception.getMessage());
        }
    }

    /* ------------------------- волна 28: новые счётчики ------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (honest(player)) {
            service.addProgress(player, AchievementMetric.BLOCKS_PLACED, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (honest(player)) {
            service.addProgress(player, AchievementMetric.ITEMS_ENCHANTED, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrewOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.BREWING
                && event.getInventory().getLocation() != null
                && event.getPlayer() instanceof Player) {
            brewingStandOwners.put(event.getInventory().getLocation(), event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrewClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getType() == InventoryType.BREWING
                && event.getInventory().getLocation() != null) {
            brewingStandOwners.remove(event.getInventory().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent event) {
        // Одна варка даёт до трёх зелий: считаем фактический результат.
        var contents = event.getContents();
        int brewed = 0;
        for (int slot = 0; slot < 3; slot++) {
            var item = contents.getItem(slot);
            if (item == null) {
                continue;
            }
            Material type = item.getType();
            if (type == Material.POTION || type == Material.SPLASH_POTION
                    || type == Material.LINGERING_POTION) {
                brewed++;
            }
        }
        if (brewed == 0) {
            return;
        }
        UUID owner = event.getBlock() == null ? null
                : brewingStandOwners.get(event.getBlock().getLocation());
        Player player = owner == null ? null : org.bukkit.Bukkit.getPlayer(owner);
        if (player != null && honest(player)) {
            service.addProgress(player, AchievementMetric.POTIONS_BREWED, brewed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerTrade(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.MERCHANT
                || event.getRawSlot() != 2 || event.getCurrentItem() == null
                || event.getCurrentItem().getType().isAir()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player && honest(player)) {
            service.addProgress(player, AchievementMetric.VILLAGER_TRADES, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (event.getItem().getType().isEdible() && honest(player)) {
            service.addProgress(player, AchievementMetric.FOOD_EATEN, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player && honest(player)) {
            service.addProgress(player, AchievementMetric.ANIMALS_TAMED, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK
                && honest(event.getPlayer())) {
            service.addProgress(event.getPlayer(), AchievementMetric.BEDS_SLEPT, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        World world = event.getPlayer().getWorld();
        if (world.getEnvironment() == World.Environment.NETHER) {
            service.addProgress(event.getPlayer(), AchievementMetric.NETHER_VISITS, 1L);
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            service.addProgress(event.getPlayer(), AchievementMetric.END_VISITS, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && event.getProjectile() != null
                && honest(player)) {
            service.addProgress(player, AchievementMetric.ARROWS_SHOT, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || event.getEntity() instanceof Player || !honest(attacker)) {
            return;
        }
        long damage = Math.max(1L, Math.round(event.getFinalDamage()));
        service.addProgress(attacker, AchievementMetric.DAMAGE_DEALT, damage);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageTaken(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !honest(player)) {
            return;
        }
        try {
            if (event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0d) {
                service.addProgress(player, AchievementMetric.SHIELD_BLOCKS, 1L);
            }
        } catch (RuntimeException ignored) {
            // Модификатор не применим к этому урону — это не ошибка.
        }
        long damage = Math.max(1L, Math.round(event.getFinalDamage()));
        service.addProgress(player, AchievementMetric.DAMAGE_TAKEN, damage);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        service.addProgress(event.getEntity(), AchievementMetric.DEATHS, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (honest(event.getPlayer())) {
            service.addProgress(event.getPlayer(), AchievementMetric.TOOLS_BROKEN, 1L);
        }
    }

    /** Атакующий: сам игрок или выпущенный им снаряд. */
    private Player attackerOf(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private boolean honest(Player player) {
        if (player == null) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (!plugin.config().countCreative()
                && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR)) {
            return false;
        }
        return true;
    }

    /**
     * Чат приходит асинхронно: обращаться к инвентарю и миру нельзя, но
     * {@code getGameMode()} у Paper-игрока безопасен, а сервис работает с
     * потокобезопасным хранилищем.
     */
    private boolean honestAsync(Player player) {
        GameMode mode = player.getGameMode();
        return plugin.config().countCreative()
                || (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR);
    }
}
