package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Жизненный цикл ядра в мире: крафт, установка в свой приват, снятие
 * владельцем и защита от чужих рук и разрушений (п.31, §2–§3, §7).
 *
 * <p>Установка: правый клик ядром по блоку — ядро становится блоком
 * (лодестоун), бинд пишется в хранилище. Снятие: владелец ломает ядро —
 * предмет возвращается с прежним идентификатором, бункер выдаётся
 * предметами по таблице тира. Чужие не могут сломать ядро, взрывы и поршни
 * его не трогают.</p>
 */
public final class GeneratorWorldListener implements Listener {

    /** Индекс «блок → владелец» для защит и снятия. */
    private record BlockKey(String world, int x, int y, int z) {
    }

    private final GeneratorPlugin plugin;
    private final Map<BlockKey, UUID> coreAt = new LinkedHashMap<>();
    private final Random random = new Random();

    public GeneratorWorldListener(GeneratorPlugin plugin) {
        this.plugin = plugin;
        for (UUID owner : plugin.store().owners()) {
            GeneratorStore.Entry entry = plugin.store().entry(owner);
            if (entry != null && entry.installed()) {
                coreAt.put(new BlockKey(entry.world(), entry.x(), entry.y(), entry.z()), owner);
            }
        }
    }

    private BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Крафт: каждое ядро получает свежий идентификатор (анти-дюп копий). */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || !(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }
        if (!plugin.recipeKey().equals(keyed.getKey())) {
            return;
        }
        event.getInventory().setResult(plugin.coreItem().build(UUID.randomUUID()));
    }

    /** Смещённый крафт выдаёт копии одного идентификатора — только по одному. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || !plugin.coreItem().isCore(result)) {
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            plugin.messages().send(event.getWhoClicked(), "craft-one-by-one");
        }
    }

    /** Установка ядра: только своё, только в приват, один генератор на игрока. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !plugin.coreItem().isCore(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID coreId = plugin.coreItem().coreIdOf(item);
        if (coreId == null) {
            plugin.messages().send(player, "place-no-id");
            return;
        }
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry != null && entry.installed()) {
            plugin.messages().send(player, "place-already-installed");
            return;
        }
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!target.getType().isReplaceable()) {
            plugin.messages().send(player, "place-blocked");
            return;
        }
        if (!GeneratorClaimsHook.available(plugin.getLogger())) {
            plugin.messages().send(player, "place-claims-required");
            return;
        }
        if (!GeneratorClaimsHook.ownClaim(plugin.getLogger(), player, target.getLocation())) {
            plugin.messages().send(player, "place-not-your-claim");
            return;
        }
        boolean installed;
        try {
            installed = plugin.store().install(player.getUniqueId(), coreId,
                    target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                    System.currentTimeMillis());
        } catch (IllegalArgumentException exception) {
            plugin.messages().send(player, "place-core-busy");
            return;
        }
        if (!installed) {
            plugin.messages().send(player, "place-already-installed");
            return;
        }
        target.setType(Material.LODESTONE, false);
        coreAt.put(key(target), player.getUniqueId());
        player.getInventory().getItemInMainHand()
                .setAmount(Math.max(0, player.getInventory().getItemInMainHand().getAmount() - 1));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.8f);
        plugin.messages().send(player, "place-ok");
    }

    /** Ломают ядро: чужим — отказ, владелец снимает с выдачей бункера. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        UUID owner = coreAt.get(key(event.getBlock()));
        if (owner == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!owner.equals(player.getUniqueId())) {
            plugin.messages().send(player, "break-protected");
            return;
        }
        GeneratorStore.Entry before = plugin.store().entry(owner);
        long units = plugin.store().remove(owner, System.currentTimeMillis());
        coreAt.remove(key(event.getBlock()));
        event.getBlock().setType(Material.AIR, false);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.6f);
        if (before != null && before.coreItem() != null) {
            player.getInventory().addItem(plugin.coreItem().build(before.coreItem()));
            giveBunker(player, before, Math.max(0L, units));
        }
        plugin.messages().send(player, "break-ok", "bunker", String.valueOf(Math.max(0L, units)));
    }

    /** Бункер — предметами по таблице тира; излишек — под ноги (принцип выдачи). */
    private void giveBunker(Player player, GeneratorStore.Entry before, long units) {
        if (units <= 0) {
            return;
        }
        GeneratorConfig.Tier tier = plugin.config().tier(before.tier());
        int luck = before.modules().getOrDefault("luck", 0);
        Map<String, Integer> items = GeneratorDrops.materialize(units, tier.drops(), luck, random);
        for (var row : items.entrySet()) {
            Material material = Material.matchMaterial(row.getKey());
            if (material == null || material.isAir()) {
                continue;
            }
            int remaining = row.getValue();
            while (remaining > 0) {
                int take = Math.min(remaining, material.getMaxStackSize());
                var leftover = player.getInventory().addItem(new ItemStack(material, take));
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
                remaining -= take;
            }
        }
    }

    /** Взрывы ядро не трогают. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> coreAt.containsKey(key(block)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> coreAt.containsKey(key(block)));
    }

    /** Поршни ядро не двигают. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> coreAt.containsKey(key(block)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> coreAt.containsKey(key(block)))) {
            event.setCancelled(true);
        }
    }
}
