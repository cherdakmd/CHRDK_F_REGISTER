package ru.cherdakmd.chrdktreasures;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * События кладоискательства: добыча, бой, стрельба, падение и ауры.
 *
 * <p>Клад выпадает только за «честный» блок: поставленный игроком блок не
 * считается (иначе камень можно ставить и ломать до мифического предмета), а
 * креатив и спектатор не качают прогрессию. Между находками действует пауза
 * {@code limits.min-seconds-between-treasures}: она не мешает обычной игре, но
 * не даёт превратить шахту в конвейер на зачарованной кирке.</p>
 *
 * <p>Событийные эффекты находок — вампиризм, поджог, двойная руда, бонус
 * опыта, снижение урона от падения — считаются здесь; атрибутные уже висят на
 * предметах и работают без плагина.</p>
 */
public final class TreasureListener implements Listener {

    private static final long ARROW_BONUS_TICKS = 20L * 30;

    private final TreasuresPlugin plugin;
    private final TreasureService service;
    private final Random random = new Random();
    private final NamespacedKey arrowBonusKey;
    private final Map<UUID, Long> lastTreasure = new ConcurrentHashMap<>();

    private MinedBlocks minedBlocks;

    public TreasureListener(TreasuresPlugin plugin, TreasureService service) {
        this.plugin = plugin;
        this.service = service;
        this.arrowBonusKey = new NamespacedKey(plugin, "arrow_bonus");
        this.minedBlocks = new MinedBlocks(
                plugin.config().placedBlockTtlSeconds() * 1000L,
                plugin.config().placedBlockMaxEntries());
    }

    /** Пересоздаёт антинакрутку после reload. */
    public void reloadAntiCheat() {
        this.minedBlocks = new MinedBlocks(
                plugin.config().placedBlockTtlSeconds() * 1000L,
                plugin.config().placedBlockMaxEntries());
    }

    /* --------------------------------- добыча --------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!honest(player) || !plugin.config().enabled()) {
            return;
        }
        Block block = event.getBlock();
        Material material = block.getType();
        if (!plugin.config().mineable(material.name())) {
            return;
        }
        if (minedBlocks.consume(player.getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis())) {
            return;
        }

        // Бонус опыта — первым: он меняет ванильный выпавший опыт, пока блок цел.
        double xpBonus = plugin.items().sum(player, TreasureEffect.Kind.XP_BONUS);
        if (xpBonus > 0.0 && event.getExpToDrop() > 0) {
            event.setExpToDrop((int) Math.round(event.getExpToDrop() * (1.0 + xpBonus)));
        }

        // Порог следующей редкости считается по блокам ДО текущего: иначе первый
        // же камень на нуле мог бы выдать клад, который по прогрессии ещё закрыт.
        long now = System.currentTimeMillis();
        Long previous = lastTreasure.get(player.getUniqueId());
        boolean coolingDown = previous != null && plugin.config().cooldownMillis() > 0
                && now - previous < plugin.config().cooldownMillis();
        TreasureTier tier = coolingDown
                ? null
                : service.rollTier(player, material, block.getY());
        service.store().addMined(player.getUniqueId().toString(), 1L);
        doubleDrop(player, block);
        if (tier == null) {
            return;
        }
        TreasureRegion region = service.regionOf(block);
        TreasureRoll.Roll roll = service.rollContent(tier, region);
        lastTreasure.put(player.getUniqueId(), now);
        TreasureService.Found found = service.give(player, tier, region, roll);
        celebrate(player, block.getLocation(), found);
    }

    /** Шанс второй порции добычи из блока — эффект DOUBLE_ORE. */
    private void doubleDrop(Player player, Block block) {
        if (!ore(block.getType())) {
            return;
        }
        double chance = plugin.items().sum(player, TreasureEffect.Kind.DOUBLE_ORE);
        if (chance <= 0.0 || random.nextDouble() >= Math.min(1.0, chance)) {
            return;
        }
        List<ItemStack> drops;
        try {
            drops = new ArrayList<>(block.getDrops(
                    player.getInventory().getItemInMainHand(), player));
        } catch (RuntimeException exception) {
            drops = List.of();
        }
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        boolean dropped = false;
        for (ItemStack drop : drops) {
            if (drop != null && !drop.getType().isAir()) {
                block.getWorld().dropItemNaturally(at, drop.clone());
                dropped = true;
            }
        }
        if (dropped) {
            plugin.messages().send(player, "double-ore");
        }
    }

    /** Руда ли это: двойная добыча не должна дублировать песок и бревна. */
    private static boolean ore(Material material) {
        if (material == null) {
            return false;
        }
        return material == Material.ANCIENT_DEBRIS || material.name().endsWith("_ORE");
    }

    /** Запоминаем поставленные блоки — с них клада не будет. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        minedBlocks.record(event.getPlayer().getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis());
    }

    /* ----------------------------------- бой ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)
                || victim instanceof org.bukkit.entity.ArmorStand || !honest(player)) {
            return;
        }
        TreasureItemDef weapon = plugin.items().defOf(
                player.getInventory().getItemInMainHand());
        if (weapon == null) {
            return;
        }
        double lifesteal = weapon.value(TreasureEffect.Kind.LIFESTEAL);
        if (lifesteal > 0.0) {
            heal(player, event.getFinalDamage() * Math.min(0.5, lifesteal));
        }
        double flame = weapon.value(TreasureEffect.Kind.FLAME);
        if (flame > 0.0 && random.nextDouble() < Math.min(1.0, flame)) {
            victim.setFireTicks(Math.max(victim.getFireTicks(), 100));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !(event.getProjectile() instanceof AbstractArrow arrow)
                || !honest(player)) {
            return;
        }
        TreasureItemDef bow = plugin.items().defOf(event.getBow());
        if (bow == null) {
            return;
        }
        double bonus = bow.value(TreasureEffect.Kind.RANGED_DAMAGE);
        if (bonus > 0.0) {
            arrow.getPersistentDataContainer().set(arrowBonusKey,
                    PersistentDataType.DOUBLE, bonus);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> clearArrowBonus(arrow), ARROW_BONUS_TICKS);
        }
        double flame = bow.value(TreasureEffect.Kind.FLAME);
        if (flame > 0.0 && random.nextDouble() < Math.min(1.0, flame)) {
            arrow.setFireTicks(100);
        }
    }

    /** Снимает метку с уже улетевшей стрелы, не падая на удалённой сущности. */
    private void clearArrowBonus(AbstractArrow arrow) {
        try {
            arrow.getPersistentDataContainer().remove(arrowBonusKey);
        } catch (RuntimeException ignored) {
            // стрела уже выгружена — метка исчезнет вместе с ней
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow)) {
            return;
        }
        Double bonus = arrow.getPersistentDataContainer().get(arrowBonusKey,
                PersistentDataType.DOUBLE);
        if (bonus == null || bonus <= 0.0) {
            return;
        }
        arrow.getPersistentDataContainer().remove(arrowBonusKey);
        event.setDamage(event.getDamage() * (1.0 + bonus));
    }

    /** Снижение урона от падения — суммарно по надетым находкам. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        double reduction = Math.min(0.8,
                plugin.items().sum(player, TreasureEffect.Kind.FALL_REDUCTION));
        if (reduction > 0.0) {
            event.setDamage(event.getDamage() * (1.0 - reduction));
        }
    }

    /* -------------------------------- служебное -------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.rememberName(event.getPlayer());
        plugin.auras().apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastTreasure.remove(event.getPlayer().getUniqueId());
        minedBlocks.prune(System.currentTimeMillis());
    }

    /* --------------------------------- события -------------------------------- */

    private void celebrate(Player player, Location location, TreasureService.Found found) {
        String tierName = plugin.messages().raw("tier." + found.tier().id());
        String itemName = found.named()
                ? plugin.messages().raw("treasure." + found.item().id() + ".name")
                : "";

        if (plugin.store().particles(player.getUniqueId().toString())) {
            particles(location, found.tier());
        }
        if (plugin.store().sounds(player.getUniqueId().toString())) {
            player.playSound(player.getLocation(), soundOf(found.tier()), 0.9f, 1.0f);
        }
        plugin.showcase().show(player, found);
        if (plugin.store().announce(player.getUniqueId().toString())) {
            if (found.named()) {
                plugin.messages().send(player, "treasure-found-named",
                        "tier", Messages.trusted(tierName),
                        "name", Messages.trusted(itemName));
            } else {
                plugin.messages().send(player, "treasure-found-loot",
                        "tier", Messages.trusted(tierName));
            }
        }

        boolean broadcast = found.tier() == TreasureTier.MYTHICAL && plugin.config().broadcastMythical()
                || found.tier() == TreasureTier.LEGENDARY && plugin.config().broadcastLegendary();
        if (broadcast) {
            String message = found.named() ? "treasure-broadcast-named" : "treasure-broadcast-loot";
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.messages().send(online, message,
                        "player", player.getName(),
                        "tier", Messages.trusted(tierName),
                        "name", Messages.trusted(itemName));
            }
            plugin.getLogger().info("[CHRDK_Treasures] " + player.getName()
                    + " нашёл " + found.tier().id() + (found.named() ? ": " + found.item().id() : ""));
        }
    }

    private void particles(Location location, TreasureTier tier) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Particle particle = switch (tier) {
            case COMMON -> Particle.HAPPY_VILLAGER;
            case RARE -> Particle.END_ROD;
            case EPIC -> Particle.ENCHANT;
            case LEGENDARY -> Particle.TOTEM_OF_UNDYING;
            case MYTHICAL -> Particle.DRAGON_BREATH;
        };
        int count = switch (tier) {
            case COMMON -> 8;
            case RARE -> 14;
            case EPIC -> 22;
            case LEGENDARY -> 32;
            case MYTHICAL -> 48;
        };
        world.spawnParticle(particle, location.add(0.5, 0.6, 0.5), count, 0.45, 0.45, 0.45, 0.02);
    }

    private static Sound soundOf(TreasureTier tier) {
        return switch (tier) {
            case COMMON -> Sound.ENTITY_ITEM_PICKUP;
            case RARE -> Sound.ENTITY_PLAYER_LEVELUP;
            case EPIC -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            case LEGENDARY -> Sound.ENTITY_ENDER_DRAGON_GROWL;
            case MYTHICAL -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
        };
    }

    private boolean honest(Player player) {
        if (player == null) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    private void heal(Player player, double amount) {
        if (amount <= 0.0) {
            return;
        }
        var health = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double max = health != null ? health.getValue() : 20.0;
        if (player.getHealth() >= max) {
            return;
        }
        try {
            player.setHealth(Math.min(max, player.getHealth() + amount));
        } catch (IllegalArgumentException ignored) {
            // здоровье изменилось под нами — лечение пропускаем
        }
    }

}
