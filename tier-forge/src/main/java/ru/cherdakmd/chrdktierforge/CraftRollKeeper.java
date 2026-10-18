package ru.cherdakmd.chrdktierforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Отложенные решения превью крафта: ролл редкости ({@code TierDef.Snapshot}) или
 * булева метка «предмет будет неопознанным».
 *
 * <p>Анти-эксплойт: превью результата (PrepareItemCraftEvent) больше НЕ роллит заново —
 * игрок, дёргая ингредиенты, видит один и тот же исход. Решение вычисляется один раз
 * на пару «игрок + тип результата» и обновляется только при реальном крафте
 * (CraftItemEvent), то есть материалы тратятся прежде, чем выпадет новая попытка.
 *
 * <p>Чистая логика, без Bukkit — покрыто unit-тестами. Данные в памяти, очищаются
 * на выходе игрока.
 *
 * @param <T> тип отложенного решения; {@code null} — валидное значение («не выпало»)
 */
final class CraftRollKeeper<T> {

    /** Отложенное решение; null-значение тоже допустимо и означает «попытка пустая». */
    private record Pending<T>(T value) {
    }

    private final Map<UUID, Map<String, Pending<T>>> byPlayer = new HashMap<>();

    /**
     * Превью: возвращает отложенное решение, при первом обращении вычисляя его
     * через supplier. Повторные вызовы с тем же ключом дают тот же результат,
     * пока не будет {@link #consume(UUID, String)}.
     *
     * @return решение (может быть {@code null} — «тира/метки не будет»).
     */
    synchronized T preview(UUID player, String key, Supplier<T> freshRoll) {
        Map<String, Pending<T>> map = byPlayer.computeIfAbsent(player, k -> new HashMap<>());
        Pending<T> pending = map.get(key);
        if (pending == null) {
            pending = new Pending<>(freshRoll.get());
            map.put(key, pending);
        }
        return pending.value();
    }

    /**
     * Завершённый крафт: изымает отложенное решение (следующее превью сделает новое).
     *
     * @return решение, которое было показано в превью (или null).
     */
    synchronized T consume(UUID player, String key) {
        Map<String, Pending<T>> map = byPlayer.get(player);
        if (map == null) return null;
        Pending<T> pending = map.remove(key);
        if (pending == null) return null;
        if (map.isEmpty()) {
            byPlayer.remove(player);
        }
        return pending.value();
    }

    /** Есть ли сейчас отложенное решение по паре игрок+ключ (для тестов/диагностики). */
    synchronized boolean hasPending(UUID player, String key) {
        Map<String, Pending<T>> map = byPlayer.get(player);
        return map != null && map.containsKey(key);
    }

    /** Выход игрока: подчистили память. */
    synchronized void clear(UUID player) {
        byPlayer.remove(player);
    }
}
