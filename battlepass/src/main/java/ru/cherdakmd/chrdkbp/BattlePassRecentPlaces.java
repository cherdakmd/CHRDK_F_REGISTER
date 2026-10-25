package ru.cherdakmd.chrdkbp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Память о недавно поставленных игроком блоках — защита от накрутки заданий
 * «поставь и сломай». Запись живёт ограниченное время и удаляется при первом
 * использовании, поэтому память не растёт бесконечно.
 *
 * <p>Класс не зависит от Bukkit: координаты приходят обычными числами, время —
 * {@code System.currentTimeMillis()} от вызывающего кода. Это позволяет проверить
 * истечение срока и лимит размера тестами.</p>
 */
final class BattlePassRecentPlaces {

    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Long> placed = new LinkedHashMap<>();

    BattlePassRecentPlaces(long ttlMillis, int maxEntries) {
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.maxEntries = Math.max(16, maxEntries);
    }

    /** Запомнить поставленный блок. */
    synchronized void record(UUID player, String world, int x, int y, int z, long now) {
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
     * Был ли блок недавно поставлен этим игроком.
     *
     * <p>Запись удаляется при первом обращении: повторное разрушение того же
     * места уже не должно скрывать прогресс.</p>
     */
    synchronized boolean consume(UUID player, String world, int x, int y, int z, long now) {
        String key = key(player, world, x, y, z);
        Long at = placed.remove(key);
        if (at == null) {
            return false;
        }
        return now - at <= ttlMillis;
    }

    /** Убрать просроченные записи. */
    synchronized void prune(long now) {
        Iterator<Map.Entry<String, Long>> iterator = placed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > ttlMillis) {
                iterator.remove();
            }
        }
    }

    synchronized int size() {
        return placed.size();
    }

    private static String key(UUID player, String world, int x, int y, int z) {
        String owner = player == null ? "?" : player.toString();
        String dimension = world == null ? "?" : world.toLowerCase(Locale.ROOT);
        return owner + '@' + dimension + ':' + x + ':' + y + ':' + z;
    }
}
