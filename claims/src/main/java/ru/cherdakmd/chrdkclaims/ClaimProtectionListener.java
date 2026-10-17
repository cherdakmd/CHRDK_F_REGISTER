package ru.cherdakmd.chrdkclaims;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Защита приватов «как на других серверах»: блоки, контейнеры, двери, вёрстаки,
 * урожай, сущности, взрывы, поршни, жидкости, воронки, огонь, таблички.
 * Со всех событий — дешёвый lookup по карте чанков; у админов есть /claimadmin bypass.
 */
public final class ClaimProtectionListener implements Listener {

    /** Двери, калитки и люки — для флага protect.doors. */
    private static final Set<Material> DOORS = Set.of(
            Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.BIRCH_DOOR, Material.JUNGLE_DOOR,
            Material.ACACIA_DOOR, Material.DARK_OAK_DOOR, Material.MANGROVE_DOOR,
            Material.CHERRY_DOOR, Material.PALE_OAK_DOOR, Material.BAMBOO_DOOR,
            Material.CRIMSON_DOOR, Material.WARPED_DOOR, Material.IRON_DOOR,
            Material.COPPER_DOOR, Material.EXPOSED_COPPER_DOOR, Material.WEATHERED_COPPER_DOOR,
            Material.OXIDIZED_COPPER_DOOR, Material.WAXED_COPPER_DOOR,
            Material.WAXED_EXPOSED_COPPER_DOOR, Material.WAXED_WEATHERED_COPPER_DOOR,
            Material.WAXED_OXIDIZED_COPPER_DOOR,
            Material.OAK_TRAPDOOR, Material.SPRUCE_TRAPDOOR, Material.BIRCH_TRAPDOOR,
            Material.JUNGLE_TRAPDOOR, Material.ACACIA_TRAPDOOR, Material.DARK_OAK_TRAPDOOR,
            Material.MANGROVE_TRAPDOOR, Material.CHERRY_TRAPDOOR, Material.PALE_OAK_TRAPDOOR,
            Material.BAMBOO_TRAPDOOR, Material.CRIMSON_TRAPDOOR, Material.WARPED_TRAPDOOR,
            Material.IRON_TRAPDOOR, Material.COPPER_TRAPDOOR, Material.EXPOSED_COPPER_TRAPDOOR,
            Material.WEATHERED_COPPER_TRAPDOOR, Material.OXIDIZED_COPPER_TRAPDOOR,
            Material.WAXED_COPPER_TRAPDOOR, Material.WAXED_EXPOSED_COPPER_TRAPDOOR,
            Material.WAXED_WEATHERED_COPPER_TRAPDOOR, Material.WAXED_OXIDIZED_COPPER_TRAPDOOR,
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE,
            Material.PALE_OAK_FENCE_GATE, Material.BAMBOO_FENCE_GATE,
            Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE
    );

    /** Кнопки/рычаги/плиты и прочий «публичный» механизм — для флага protect.redstone. */
    private static final Set<Material> REDSTONE = Set.of(
            Material.LEVER,
            Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON,
            Material.BIRCH_BUTTON, Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON,
            Material.DARK_OAK_BUTTON, Material.MANGROVE_BUTTON, Material.CHERRY_BUTTON,
            Material.PALE_OAK_BUTTON, Material.BAMBOO_BUTTON, Material.CRIMSON_BUTTON,
            Material.WARPED_BUTTON, Material.POLISHED_BLACKSTONE_BUTTON,
            Material.STONE_PRESSURE_PLATE, Material.OAK_PRESSURE_PLATE,
            Material.SPRUCE_PRESSURE_PLATE, Material.BIRCH_PRESSURE_PLATE,
            Material.JUNGLE_PRESSURE_PLATE, Material.ACACIA_PRESSURE_PLATE,
            Material.DARK_OAK_PRESSURE_PLATE, Material.MANGROVE_PRESSURE_PLATE,
            Material.CHERRY_PRESSURE_PLATE, Material.PALE_OAK_PRESSURE_PLATE,
            Material.BAMBOO_PRESSURE_PLATE, Material.CRIMSON_PRESSURE_PLATE,
            Material.WARPED_PRESSURE_PLATE, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
            Material.NOTE_BLOCK, Material.REDSTONE_WIRE, Material.TRIPWIRE_HOOK
    );

    /**
     * Вёрстаки и «портящие» блоки: чужаку нельзя открывать и использовать, иначе
     * наковальня изнашивается, маяк перенастраивается, якорь взрывается, торт съедается.
     * Цветные свечи и кашпо ловятся по имени материала (см. {@link #isWorkstation}).
     */
    private static final Set<Material> WORKSTATIONS = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.BEACON, Material.RESPAWN_ANCHOR,
            Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.GRINDSTONE,
            Material.ENCHANTING_TABLE, Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE,
            Material.LOOM, Material.STONECUTTER, Material.SMOKER, Material.BLAST_FURNACE,
            Material.FURNACE, Material.BREWING_STAND,
            Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON,
            Material.POWDER_SNOW_CAULDRON,
            Material.JUKEBOX, Material.NOTE_BLOCK, Material.CAKE, Material.CANDLE, Material.CANDLE_CAKE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.DECORATED_POT,
            Material.COMPOSTER, Material.TNT, Material.LECTERN, Material.FLOWER_POT,
            Material.CHISELED_BOOKSHELF
    );

    private final ClaimsPlugin plugin;
    /** Троттлинг вежливых отказов: player → последний показ (millis). */
    private final Map<UUID, Long> denyNotified = new HashMap<>();

    public ClaimProtectionListener(ClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers --

    private Claim claimAt(Block block) {
        if (block == null) {
            return null;
        }
        return plugin.store().claimAt(block.getWorld().getName(),
                block.getX() >> 4, block.getZ() >> 4);
    }

    /** Может ли игрок действовать в чанке блока (с учётом обхода админа). */
    private boolean may(Player player, Claim claim) {
        if (claim == null) {
            return true;
        }
        return claim.canBuild(player.getName()) || plugin.isBypassing(player);
    }

    /** Первый чужой приват среди блоков (для ведра: клик и соседняя клетка). */
    private Claim foreignAt(Player player, Block... blocks) {
        for (Block block : blocks) {
            Claim claim = claimAt(block);
            if (claim != null && !may(player, claim)) {
                return claim;
            }
        }
        return null;
    }

    private void deny(Player player, Claim claim, Block block, String messageKey) {
        if (claim == null || may(player, claim)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = denyNotified.get(player.getUniqueId());
        if (last == null || now - last > 2000) {
            denyNotified.put(player.getUniqueId(), now);
            plugin.messages().send(player, messageKey, "owner", claim.owner);
        }
    }

    /** Вёрстаки, свечи, кашпо и прочие «используемые» блоки. */
    private boolean isWorkstation(Material type) {
        if (WORKSTATIONS.contains(type)) {
            return true;
        }
        String name = type.name();
        return name.startsWith("POTTED_") || name.endsWith("_CANDLE")
                || name.endsWith("_CANDLE_CAKE");
    }

    private boolean isSpawnEgg(ItemStack item) {
        return item != null && item.getType().name().endsWith("_SPAWN_EGG");
    }

    private Block inventoryBlock(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        Location location = inventory.getLocation();
        return location == null ? null : location.getBlock();
    }

    // -------------------------------------------------------------- блоки --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Claim claim = claimAt(event.getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-break");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Claim claim = claimAt(event.getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-place");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Claim claim = foreignAt(event.getPlayer(), event.getBlock(),
                event.getBlock().getRelative(event.getBlockFace()));
        if (claim != null) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-place");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Claim claim = foreignAt(event.getPlayer(), event.getBlock(),
                event.getBlock().getRelative(event.getBlockFace()));
        if (claim != null) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-break");
        }
    }

    /**
     * Урожай, который снимается без разрушения блока: ягоды, тыквы-фонари и т.п.
     * Раньше чужаки могли обобрать ферму, не ломая ни одного блока.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        Claim claim = claimAt(event.getHarvestedBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getHarvestedBlock(), "denied-break");
        }
    }

    /** Костная мука на чужой ферме: ускоряет рост и расходует чужие посадки. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return; // разбрасыватель/естественный рост
        }
        Claim claim = claimAt(event.getBlock());
        if (!may(player, claim)) {
            event.setCancelled(true);
            deny(player, claim, event.getBlock(), "denied-interact");
        }
    }

    // ---------------------------------------------------------- interact --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            // утрамбовка грядок
            if (block.getType() == Material.FARMLAND) {
                Claim claim = claimAt(block);
                if (!may(event.getPlayer(), claim)) {
                    event.setCancelled(true);
                    deny(event.getPlayer(), claim, block, "denied-interact");
                }
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Claim claim = claimAt(block);
        if (claim == null || may(event.getPlayer(), claim)) {
            return;
        }
        Material type = block.getType();
        if (plugin.config().workstations && isWorkstation(type)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-interact");
            return;
        }
        // контейнеры (сундуки, печки, стойки варки, кафедры и т.п.)
        BlockState state = block.getState(false);
        if (state instanceof InventoryHolder || type == Material.LECTERN) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-container");
            return;
        }
        if (plugin.config().doors && DOORS.contains(type)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-interact");
            return;
        }
        if (plugin.config().redstone && REDSTONE.contains(type)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-interact");
            return;
        }
        if (plugin.config().entities && isSpawnEgg(event.getItem())) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignOpen(PlayerSignOpenEvent event) {
        Claim claim = claimAt(event.getSign().getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getSign().getBlock(), "denied-sign");
        }
    }

    /** Вытащить книгу из чужой кафедры. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent event) {
        Block block = event.getLectern().getBlock();
        Claim claim = claimAt(block);
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, block, "denied-container");
        }
    }

    // ------------------------------------------------------------ сущности --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        var entity = event.getRightClicked();
        if (!(entity instanceof Animals) && !(entity instanceof ArmorStand)
                && !(entity instanceof Hanging) && !(entity instanceof Vehicle)
                && !(entity instanceof Villager) && !(entity instanceof WanderingTrader)) {
            return;
        }
        Claim claim = claimAt(entity.getLocation().getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, entity.getLocation().getBlock(), "denied-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Claim claim = claimAt(event.getRightClicked().getLocation().getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getRightClicked().getLocation().getBlock(),
                    "denied-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getEntity().getLocation().getBlock(),
                    "denied-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = damageSource(event.getDamager());
        if (attacker == null) {
            return;
        }
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (claim == null) {
            return;
        }
        if (plugin.config().pvp && event.getEntity() instanceof Player) {
            if (!may(attacker, claim)) {
                event.setCancelled(true);
                deny(attacker, claim, event.getEntity().getLocation().getBlock(), "denied-entity");
            }
            return;
        }
        if (!plugin.config().entities) {
            return;
        }
        var victim = event.getEntity();
        if (victim instanceof Animals || victim instanceof ArmorStand || victim instanceof Hanging
                || victim instanceof Vehicle || victim instanceof Villager
                || victim instanceof WanderingTrader) {
            if (!may(attacker, claim)) {
                event.setCancelled(true);
                deny(attacker, claim, victim.getLocation().getBlock(), "denied-entity");
            }
        }
    }

    private Player damageSource(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Поставить лодку/вагонетку/кристалл/стойку в чужом привате. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Claim claim = claimAt(event.getBlock());
        if (!may(player, claim)) {
            event.setCancelled(true);
            deny(player, claim, event.getBlock(), "denied-place");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Player attacker = damageSource(event.getRemover());
        if (attacker == null) {
            return; // взрыв/физика — решает onHangingBreak по причине
        }
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (claim == null) {
            return;
        }
        if (!may(attacker, claim)) {
            event.setCancelled(true);
            deny(attacker, claim, event.getEntity().getLocation().getBlock(), "denied-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (claim == null) {
            return;
        }
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            if (plugin.config().explosions) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getCause() == HangingBreakEvent.RemoveCause.ENTITY) {
            return; // личный виновник обрабатывается в onHangingBreakByEntity
        }
        if (plugin.config().entities) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getEntity().getLocation().getBlock(),
                    "denied-place");
        }
    }

    // ------------------------------------------------------- мир и бедствия --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!plugin.config().explosions) {
            return;
        }
        event.blockList().removeIf(block -> claimAt(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.config().explosions) {
            return;
        }
        event.blockList().removeIf(block -> claimAt(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (plugin.config().fire && claimAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!plugin.config().fire) {
            return;
        }
        Claim claim = claimAt(event.getBlock());
        if (claim == null) {
            return;
        }
        Player igniter = event.getPlayer();
        if (igniter != null) {
            if (!may(igniter, claim)) {
                event.setCancelled(true);
                deny(igniter, claim, event.getBlock(), "denied-place");
            }
            return;
        }
        // растекшаяся лава/молния/разнос пожара — в привате гасим
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        if (!plugin.config().fluids) {
            return;
        }
        Claim from = claimAt(event.getBlock());
        Claim to = claimAt(event.getToBlock());
        if (to != null && (from == null || !from.isOwner(to.owner))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.config().pistons) {
            return;
        }
        Claim pistonClaim = claimAt(event.getBlock());
        for (Block moved : event.getBlocks()) {
            if (crossesBorder(pistonClaim, claimAt(moved))
                    || crossesBorder(pistonClaim, claimAt(moved.getRelative(event.getDirection())))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.config().pistons) {
            return;
        }
        Claim pistonClaim = claimAt(event.getBlock());
        for (Block moved : event.getBlocks()) {
            if (crossesBorder(pistonClaim, claimAt(moved))
                    || crossesBorder(pistonClaim, claimAt(moved.getRelative(event.getDirection())))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Блок уходит через границу: владелец точки не совпадает с владельцем поршня. */
    private boolean crossesBorder(Claim base, Claim point) {
        if (point == null) {
            return false;
        }
        return base == null || !base.isOwner(point.owner);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!plugin.config().mobGriefing || event.getEntity() instanceof Player
                || event.getEntity() instanceof FallingBlock) {
            return; // песок/гравий/наковальня должны падать и в приватах
        }
        Claim claim = claimAt(event.getBlock());
        if (claim != null) {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------- воронки и транспорт --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPull(InventoryMoveItemEvent event) {
        if (!plugin.config().hoppers) {
            return;
        }
        Claim sourceClaim = claimAt(inventoryBlock(event.getSource()));
        Claim destClaim = claimAt(inventoryBlock(event.getDestination()));
        if (sourceClaim == null && destClaim == null) {
            return;
        }
        // воронка должна стоять в привате того же владельца, иначе пересылка запрещена
        Claim hopperClaim = claimAt(inventoryBlock(event.getInitiator()));
        if (sourceClaim != null && (hopperClaim == null || !hopperClaim.isOwner(sourceClaim.owner))) {
            event.setCancelled(true);
            return;
        }
        if (destClaim != null && (hopperClaim == null || !hopperClaim.isOwner(destClaim.owner))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Claim claim = claimAt(event.getVehicle().getLocation().getBlock());
        if (claim == null) {
            return;
        }
        Player attacker = damageSource(event.getAttacker());
        if (attacker == null || !may(attacker, claim)) {
            event.setCancelled(true);
            if (attacker != null) {
                deny(attacker, claim, event.getVehicle().getLocation().getBlock(), "denied-entity");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        denyNotified.remove(event.getPlayer().getUniqueId());
    }
}
