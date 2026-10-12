package ru.cherdakmd.chrdktierforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Отложенные роллы редкости при крафте.
 *
 * <p>Анти-эксплойт: превью результата (PrepareItemCraftEvent) больше НЕ роллит заново —
 * игрок, дёргая ингредиенты, видит один и тот же исход. Ролл вычисляется один раз
 * на пару «игрок + тип результата» и обновляется только при реальном крафте
 * (CraftItemEvent), то есть материалы итратятся прежде, чем выпадет новая попытка.
 *
 * <p>Чистая логика, без Bukkit — покрыто unit-тестами. Данные в памяти, очищаются
 * на выходе игрока.
 */
final class CraftRollKeeper {

    /** Пустой ролл (тира не выпало) — сигнал, что попытка уже совершена. */
    private record Pending(TierDef.Snapshot snapshot) {
        static final Pending EMPTY = new Pending(null);
    }

    private final Map<UUID, Map<String, Pending>> byPlayer = new HashMap<>();

    /**
     * Превью: возвращает отложенный ролл, при первом обращении вычисляя его через supplier.
     * Повторные вызовы с тем же ключом дают тот же результат, пока не будет consume().
     *
     * @return снапшот тира или null («тира не будет»).
     */
    synchronized TierDef.Snapshot preview(UUID player, String key, Supplier<TierDef.Snapshot> freshRoll) {
        Map<String, Pending> map = byPlayer.computeIfAbsent(player, k -> new HashMap<>());
        Pending pending = map.get(key);
        if (pending == null) {
            TierDef.Snapshot snapshot = freshRoll.get();
            pending = snapshot == null ? Pending.EMPTY : new Pending(snapshot);
            map.put(key, pending);
        }
        return pending.snapshot();
    }

    /**
     * Завершённый крафт: изымает отложенный ролл (следующее превью сделает новый).
     *
     * @return снапшот, который был показан в превью (или null).
     */
    synchronized TierDef.Snapshot consume(UUID player, String key) {
        Map<String, Pending> map = byPlayer.get(player);
        if (map == null) return null;
        Pending pending = map.remove(key);
        if (pending == null) return null;
        if (map.isEmpty()) {
            byPlayer.remove(player);
        }
        return pending.snapshot();
    }

    /** Есть ли сейчас отложенный ролл по паре игрок+ключ (для тестов/диагностики). */
    synchronized boolean hasPending(UUID player, String key) {
        Map<String, Pending> map = byPlayer.get(player);
        return map != null && map.containsKey(key);
    }

    /** Выход игрока: подчистили память. */
    synchronized void clear(UUID player) {
        byPlayer.remove(player);
    }
}
