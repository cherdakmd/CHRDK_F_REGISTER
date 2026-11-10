package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Снимок изменённых блоков и откат. Катастрофы меняют мир через
 * {@code Block.setType(..., false)} — это не порождает {@code BlockBreakEvent},
 * но и не оставляет серверу историю. Поэтому перед каждым изменением сохраняем
 * {@link BlockData}, а по таймеру (или команде {@code /disastersadmin restore})
 * возвращаем мир в исходное состояние.
 *
 * <p>Вызывается только на главном потоке сервера; размер снимка ограничен
 * {@code disasters.max-blocks}, чтобы память не росла вместе с радиусом.</p>
 */
public final class DisasterRestore {

    private record BlockKey(String world, int x, int y, int z) {
    }

    private final Map<BlockKey, BlockData> snapshot = new LinkedHashMap<>();
    private final int limit;
    private final Logger logger;

    public DisasterRestore(int limit, Logger logger) {
        this.limit = Math.max(1, limit);
        this.logger = logger;
    }

    public boolean canRemember() {
        return snapshot.size() < limit;
    }

    public void remember(Block block) {
        if (block == null || !canRemember()) {
            return;
        }
        BlockKey key = new BlockKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        snapshot.putIfAbsent(key, block.getBlockData().clone());
    }

    /** @return сколько блоков удалось вернуть. */
    public int restoreAll() {
        int restored = 0;
        for (Map.Entry<BlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            // Не перетираем то, что игрок успел построить на этом месте сам.
            if (!block.isEmpty() && !block.getType().isAir()) {
                continue;
            }
            block.setBlockData(entry.getValue(), false);
            restored++;
        }
        int total = snapshot.size();
        snapshot.clear();
        if (total > 0 && logger != null) {
            logger.info("[CHRDK_Disasters] восстановлено блоков: " + restored + " из " + total);
        }
        return restored;
    }

    public int size() {
        return snapshot.size();
    }
}
