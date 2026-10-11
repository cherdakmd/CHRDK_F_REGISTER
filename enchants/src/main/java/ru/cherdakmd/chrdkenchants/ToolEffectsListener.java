package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Кирки/инструменты: автоплавка (руда → слиток сразу), жилодробитель (вынос целой жилы
 * при крадушке), дробитель (3×3/3×3×2 при крадушке), рука Мидаса (шанс удвоения минералов).
 */
public final class ToolEffectsListener implements Listener {

    private static final Set<Material> SMELTABLES = Set.of(
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.ANCIENT_DEBRIS);

    private static final Set<Material> ORES = EnumSet.of(
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_QUARTZ_ORE);

    private final EnchantsPlugin plugin;

    public ToolEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Map<String, Integer> enchants = plugin.items().read(tool);
        if (enchants.isEmpty()) return;

        // Рука Мидаса
        int midas = enchants.getOrDefault("midas", 0);
        if (midas > 0 && ORES.contains(block.getType())
                && roll(EnchantParams.chancePercent("midas", midas))) {
            block.getDrops(tool, player).forEach(drop ->
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop));
            player.playSound(block.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
        }

        boolean smelted = false;
        // Автоплавка
        if (enchants.containsKey("autosmelt") && SMELTABLES.contains(block.getType())) {
            smelted = true;
            event.setDropItems(false);
            ItemStack ingot = smeltDropFor(block.getType());
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), ingot);
            event.setExpToDrop(event.getExpToDrop() + 1);
        }

        // Мега-режимы требуют крадушку (защита от случайных прорывов при стройке)
        if (!player.isSneaking()) {
            return;
        }
        int vein = enchants.getOrDefault("veinminer", 0);
        if (vein > 0 && ORES.contains(block.getType())) {
            breakVein(player, block, tool, block.getType(),
                    EnchantParams.value("veinminer", vein), enchants.containsKey("autosmelt"));
            return;
        }
        int excavator = enchants.getOrDefault("excavator", 0);
        if (excavator > 0 && !block.getDrops(tool).isEmpty()) {
            breakArea(player, block, tool, excavator);
        }
    }

    private boolean roll(int chancePercent) {
        return chancePercent > 0 && plugin.service().random().nextInt(100) < chancePercent;
    }

    private static ItemStack smeltDropFor(Material ore) {
        return switch (ore) {
            case IRON_ORE, DEEPSLATE_IRON_ORE -> new ItemStack(Material.IRON_INGOT);
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> new ItemStack(Material.GOLD_INGOT);
            case COPPER_ORE, DEEPSLATE_COPPER_ORE -> new ItemStack(Material.COPPER_INGOT, 3);
            case ANCIENT_DEBRIS -> new ItemStack(Material.NETHERITE_SCRAP);
            default -> new ItemStack(ore);
        };
    }

    /** BFS по жиле одного типа блока; smeltAll — дополнительная автоплавка по пути. */
    private void breakVein(Player player, Block start, ItemStack tool, Material veinType,
                           int maxBlocks, boolean smeltAll) {
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int broken = 1; // стартовый блок уже событие вскрытия
        while (!queue.isEmpty() && broken < maxBlocks) {
            Block current = queue.removeFirst();
            for (Block neighbor : neighbors(current)) {
                if (broken >= maxBlocks) break;
                if (!seen.add(neighbor) || neighbor.getType() != veinType) continue;
                queue.add(neighbor);
                broken++;
                breakWithTool(player, neighbor, tool, smeltAll && SMELTABLES.contains(veinType));
            }
        }
    }

    /** 3×3 плоскость (вторым уровнем — ещё слой глубже) по направлению взгляда. */
    private void breakArea(Player player, Block center, ItemStack tool, int level) {
        org.bukkit.util.Vector dir = player.getLocation().getDirection();
        int offsetX = Math.abs(dir.getX()) > Math.abs(dir.getZ()) ? 1 : 0;
        int offsetZ = offsetX == 0 ? 1 : 0;
        boolean lookingVertical = Math.abs(dir.getY()) > 0.6;
        int layers = level >= 2 ? 2 : 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    for (int depth = 0; depth < layers; depth++) {
                        Block target;
                        if (lookingVertical) {
                            target = center.getRelative(dx, depth, dz);
                        } else {
                            target = center.getRelative(dx * (1 - offsetX) + depth * offsetX,
                                    dy, dz * (1 - offsetZ) + depth * offsetZ);
                        }
                        if (breakable(target)) {
                            breakWithTool(player, target, tool, false);
                        }
                    }
                }
            }
        }
    }

    private static boolean breakable(Block block) {
        return !block.isLiquid() && !block.getType().isAir()
                && block.getType().getHardness() > 0
                && block.getType().getHardness() < 30 && block.getType() != Material.OBSIDIAN;
    }

    private void breakWithTool(Player player, Block block, ItemStack tool, boolean smelt) {
        if (smelt) {
            CollectionWrap.drops(block, tool).forEach(drop ->
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop));
            block.setType(Material.AIR);
        } else {
            block.breakNaturally(tool, true);
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR);
            }
        }
        damageTool(player, tool);
    }

    /** Износ "как у ванили": единица на блок (виржание недорогих блоков балансом носит DPS). */
    private void damageTool(Player player, ItemStack tool) {
        if (tool.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable) {
            tool.damage(1, player);
        }
    }

    private static Block[] neighbors(Block block) {
        return new Block[]{
                block.getRelative(1, 0, 0), block.getRelative(-1, 0, 0),
                block.getRelative(0, 1, 0), block.getRelative(0, -1, 0),
                block.getRelative(0, 0, 1), block.getRelative(0, 0, -1),
                block.getRelative(1, 1, 0), block.getRelative(-1, 1, 0),
                block.getRelative(0, 1, 1), block.getRelative(0, 1, -1),
                block.getRelative(1, 0, 1), block.getRelative(-1, 0, -1),
                block.getRelative(1, 0, -1), block.getRelative(-1, 0, 1),
        };
    }

    /** Маленькая обёртка для читаемости. */
    private static final class CollectionWrap {
        static java.util.Collection<ItemStack> drops(Block block, ItemStack tool) {
            return block.getDrops(tool);
        }
    }
}
