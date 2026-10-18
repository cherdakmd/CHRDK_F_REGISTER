package ru.cherdakmd.chrdktierforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш «у игрока есть ковка в экипировке» для пульса (1.4.91).
 *
 * <p>Каждые 2 секунды пульс читал PDC всех шести слотов у всех онлайн-игроков,
 * хотя у большинства ковки нет вовсе. Кэш запоминает отрицательный ответ на
 * {@code ttlMillis} и сбрасывается любым изменением инвентаря: клик, закрытие
 * окна, смена слота, обмен рук, подбор и выброс предмета, смерть и респаун.
 * Игрок с ковкой по-прежнему пересканируется каждый пульс — иначе эффекты
 * («Теплокровность», «Мастеровой») переставали бы обновляться.
 *
 * <p>Чистая логика, без Bukkit — покрыто {@link ForgeGearCacheTest}.
 */
final class ForgeGearCache {

    private record Entry(boolean forged, long expiresAt) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;

    ForgeGearCache(long ttlMillis) {
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    /**
     * Свежий ответ кэша.
     *
     * @return {@code null} — записи нет или срок истёк (нужен скан);
     *         {@code true}/{@code false} — известный ответ.
     */
    Boolean fresh(UUID player, long now) {
        Entry entry = entries.get(player);
        if (entry == null || now >= entry.expiresAt()) {
            return null;
        }
        return entry.forged();
    }

    /** Запомнить результат скана на {@code ttlMillis} вперёд. */
    void put(UUID player, boolean forged, long now) {
        entries.put(player, new Entry(forged, now + ttlMillis));
    }

    /** Инвентарь изменился — следующий пульс обязан пересканировать игрока. */
    void invalidate(UUID player) {
        entries.remove(player);
    }

    /** Полный сброс (выключение/перезагрузка конфига). */
    void clear() {
        entries.clear();
    }

    /** Сколько записей сейчас в кэше (для диагностики/тестов). */
    int size() {
        return entries.size();
    }
}
