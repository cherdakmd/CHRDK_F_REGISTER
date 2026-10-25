package ru.cherdakmd.chrdktreasures;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Память о недавно поставленных блоках — защита от «поставь и сломай».
 *
 * <p>Клад выпадает за блок, поэтому без этой памяти игрок ставил бы камень и
 * ломал его снова, пока не выпадет мифический предмет. Запись живёт
 * {@code limits.placed-block-seconds}, удаляется при первом обращении и
 * ограничена по размеру.</p>
 *
 * <p>Класс без Bukkit-типов: координаты приходят числами, время — от
 * вызывающего кода, поэтому TTL и лимит проверяются юнит-тестами.</p>
 */
public final class MinedBlocks {

    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Long> placed = new LinkedHashMap<>();

    public MinedBlocks(long ttlMillis, int maxEntries) {
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.maxEntries = Math.max(64, maxEntries);
    }

    /** Запомнить поставленный блок. */
    public synchronized void record(UUID player, String world, int x, int y, int z, long now) {
        prune(now);
        placed.put(key(player, world, x, y, z), now);
        while (placed.size() > maxEntries) {
            Iterator<Map.Entry<String, Long>> iterator = placed.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Был ли блок недавно поставлен этим игроком. Запись удаляется сразу:
     * второй раз игрок ломает уже настоящий, не поставленный им блок.
     */
    public synchronized boolean consume(UUID player, String world, int x, int y, int z, long now) {
        String key = key(player, world, x, y, z);
        Long at = placed.remove(key);
        return at != null && now - at <= ttlMillis;
    }

    /** Убрать просроченные записи. */
    public synchronized void prune(long now) {
        Iterator<Map.Entry<String, Long>> iterator = placed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > ttlMillis) {
                iterator.remove();
            }
        }
    }

    public synchronized int size() {
        return placed.size();
    }

    public synchronized void clear() {
        placed.clear();
    }

    private static String key(UUID player, String world, int x, int y, int z) {
        String owner = player == null ? "?" : player.toString();
        String dimension = world == null ? "?" : world.toLowerCase(Locale.ROOT);
        return owner + '@' + dimension + ':' + x + ':' + y + ':' + z;
    }
}
