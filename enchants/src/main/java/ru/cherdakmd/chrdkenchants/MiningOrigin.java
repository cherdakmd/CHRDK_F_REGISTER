package ru.cherdakmd.chrdkenchants;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.persistence.PersistentDataType;

/** Постоянные отрицательные метки руды: без TTL, удаления при добыче и загрузки чужих чанков. */
final class MiningOrigin implements Listener {
    private final NamespacedKey positions, tainted;
    MiningOrigin(EnchantsPlugin plugin) {
        this(new NamespacedKey(plugin, "placed_ore_positions_v1"), new NamespacedKey(plugin, "placed_ore_chunk_v1"));
    }
    MiningOrigin(NamespacedKey positions, NamespacedKey tainted) {
        this.positions = positions; this.tainted = tainted;
    }
    private boolean loaded(Block block) { return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4); }
    boolean denied(Block block) {
        if (!loaded(block)) return true;
        var data = block.getChunk().getPersistentDataContainer();
        if (data.has(tainted)) return true;
        if (!data.has(positions)) return false;
        if (!data.has(positions, PersistentDataType.LONG_ARRAY)) return true;
        long[] values = data.get(positions, PersistentDataType.LONG_ARRAY);
        if (!MiningPolicy.validPositions(values, block.getWorld().getMinHeight(), block.getWorld().getMaxHeight())) return true;
        long at = MiningPolicy.position(block.getX(), block.getY(), block.getZ());
        for (long value : values) if (value == at) return true;
        return false;
    }
    void mark(Block block) {
        if (!loaded(block)) return;
        var data = block.getChunk().getPersistentDataContainer();
        if (data.has(tainted)) return;
        long[] values = data.has(positions) ? data.has(positions, PersistentDataType.LONG_ARRAY)
                ? data.get(positions, PersistentDataType.LONG_ARRAY) : null : new long[0];
        long[] result = !MiningPolicy.validPositions(values, block.getWorld().getMinHeight(), block.getWorld().getMaxHeight()) ? null : MiningPolicy.mark(values, MiningPolicy.position(block.getX(), block.getY(), block.getZ()));
        if (result == null) { data.set(tainted, PersistentDataType.BYTE, (byte) 1); data.remove(positions); }
        else data.set(positions, PersistentDataType.LONG_ARRAY, result);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (event instanceof org.bukkit.event.block.BlockMultiPlaceEvent multi) {
            for (var state : multi.getReplacedBlockStates()) {
                Block block = state.getBlock();
                if (MiningPolicy.ore(block.getType().name())) mark(block);
            }
        } else if (MiningPolicy.ore(event.getBlockPlaced().getType().name())) mark(event.getBlockPlaced());
    }
    private boolean piston(java.util.List<Block> blocks, org.bukkit.block.BlockFace direction) {
        // Консервативно отмечаем исходную позицию и обе соседние по оси поршня.
        // Лишняя метка лишь запрещает бонус; ни один блок или обычная добыча не меняются.
        for (Block block : blocks) if (MiningPolicy.ore(block.getType().name())) {
            var front = block.getRelative(direction); var back = block.getRelative(direction.getOppositeFace());
            if (!loaded(front) || !loaded(back)) return false;
        }
        for (Block block : blocks) if (MiningPolicy.ore(block.getType().name())) {
            mark(block); mark(block.getRelative(direction)); mark(block.getRelative(direction.getOppositeFace()));
        }
        return true;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) { if (!piston(event.getBlocks(), event.getDirection())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) { if (!piston(event.getBlocks(), event.getDirection())) event.setCancelled(true); }
}
