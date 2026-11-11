package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Снимок оригинального и последнего установленного катастрофой BlockData.
 * Откатываем только неизменённое с тех пор содержимое: вода, огонь и снежные
 * слои не остаются в мире, а поздняя постройка игрока не затирается.
 */
public final class DisasterRestore {

    private record BlockKey(String world, int x, int y, int z) {
    }

    private record Snapshot(BlockData original, BlockData applied) {
    }

    private final Map<BlockKey, Snapshot> snapshot = new LinkedHashMap<>();
    private final int limit;
    private final Logger logger;

    public DisasterRestore(int limit, Logger logger) {
        this.limit = Math.max(1, limit);
        this.logger = logger;
    }

    public boolean canRemember() {
        return snapshot.size() < limit;
    }

    /** Вызывается до первого изменения конкретного блока. */
    public void remember(Block block) {
        if (block == null || !canRemember()) {
            return;
        }
        BlockKey key = key(block);
        snapshot.putIfAbsent(key, new Snapshot(block.getBlockData().clone(), null));
    }

    /** Фиксирует фактический результат, чтобы откат не затирал чужие изменения. */
    public void markChanged(Block block) {
        if (block == null) {
            return;
        }
        BlockKey key = key(block);
        Snapshot old = snapshot.get(key);
        if (old != null) {
            snapshot.put(key, new Snapshot(old.original(), block.getBlockData().clone()));
        }
    }

    /** Чистое правило отката, доступное JUnit без серверного lifecycle. */
    public static boolean shouldRestore(BlockData current, BlockData applied) {
        return current != null && applied != null && current.matches(applied);
    }

    /** @return сколько блоков удалось вернуть. */
    public int restoreAll() {
        int restored = 0;
        int preserved = 0;
        Iterator<Map.Entry<BlockKey, Snapshot>> iterator = snapshot.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Snapshot> entry = iterator.next();
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                // Не выбрасываем единственный снимок, если мир временно выгружен.
                preserved++;
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (shouldRestore(block.getBlockData(), entry.getValue().applied())) {
                block.setBlockData(entry.getValue().original(), false);
                restored++;
            }
            // Изменённый игроком блок больше не принадлежит снимку катастрофы.
            iterator.remove();
        }
        if ((!snapshot.isEmpty() || restored > 0) && logger != null) {
            logger.info("[CHRDK_Disasters] восстановлено блоков: " + restored
                    + "; ожидают загрузки мира: " + preserved);
        }
        return restored;
    }

    public int size() {
        return snapshot.size();
    }

    private static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
