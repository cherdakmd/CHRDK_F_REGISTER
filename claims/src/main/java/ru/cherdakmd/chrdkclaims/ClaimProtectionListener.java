package ru.cherdakmd.chrdkclaims;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Защита приватов «как на других серверах»: блоки, контейнеры, двери, сущности,
 * взрывы, поршни, жидкости, воронки, огонь, таблички. Со всех событий — дешёвый
 * lookup по карте чанков; у админов есть /claimadmin bypass.
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

    private final ClaimsPlugin plugin;
    /** Троттлинг вежливых отказов: player → последний показ (millis). */
    private final Map<java.util.UUID, Long> denyNotified = new HashMap<>();

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

    private void deny(Player player, Claim claim, Block block, String messageKey) {
        if (may(player, claim)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = denyNotified.get(player.getUniqueId());
        if (last == null || now - last > 2000) {
            denyNotified.put(player.getUniqueId(), now);
            plugin.messages().send(player, messageKey, "owner", claim.owner);
        }
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
        Claim claim = claimAt(event.getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-place");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Claim claim = claimAt(event.getBlock());
        if (!may(event.getPlayer(), claim)) {
            event.setCancelled(true);
            deny(event.getPlayer(), claim, event.getBlock(), "denied-break");
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

    // ------------------------------------------------------------ сущности --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        var entity = event.getRightClicked();
        if (!(entity instanceof Animals) && !(entity instanceof ArmorStand)
                && !(entity instanceof Hanging) && !(entity instanceof Vehicle)) {
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
                || victim instanceof Vehicle || victim instanceof org.bukkit.entity.Villager
                || victim instanceof org.bukkit.entity.WanderingTrader) {
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!plugin.config().entities) {
            return;
        }
        Player attacker = damageSource(event.getRemover());
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (claim == null) {
            return;
        }
        if (attacker == null || !may(attacker, claim)) {
            // без личного виновника (взрыв/стрела скелета) — тоже защищаем
            event.setCancelled(true);
            if (attacker != null) {
                deny(attacker, claim, event.getEntity().getLocation().getBlock(), "denied-entity");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        // «природные» причины (взрыв, физика) не должны ронять рамки в привате
        Claim claim = claimAt(event.getEntity().getLocation().getBlock());
        if (claim != null && event.getCause() != HangingBreakEvent.RemoveCause.ENTITY) {
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
            Claim movedClaim = claimAt(moved);
            Claim targetClaim = claimAt(moved.getRelative(event.getDirection()));
            if (crossesBorder(pistonClaim, movedClaim) || crossesBorder(pistonClaim, targetClaim)) {
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
            if (crossesBorder(pistonClaim, claimAt(moved))) {
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
        if (!plugin.config().mobGriefing || event.getEntity() instanceof Player) {
            return;
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
        Block source = holderBlock(event.getSource().getHolder());
        Claim sourceClaim = claimAt(source);
        if (sourceClaim == null) {
            return;
        }
        // воронка должна стоять в привате того же владельца, иначе вытягивание запрещено
        Block hopper = holderBlock(event.getInitiator().getHolder());
        Claim hopperClaim = claimAt(hopper);
        if (hopperClaim == null || !hopperClaim.isOwner(sourceClaim.owner)) {
            event.setCancelled(true);
        }
    }

    private Block holderBlock(InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            return state.getBlock();
        }
        if (holder instanceof org.bukkit.entity.Entity entity) {
            return entity.getLocation().getBlock();
        }
        return null;
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
}
