package ru.cherdakmd.chrdkclasses;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пульс сравнивает собственный уровень класса с прошлым наблюдением.
 * Первое наблюдение молчаливое: вход и перенос не повторяют награды.
 */
final class ClassProgress {

    /** Потолок записей: защита от утечки, если выход игрока не дошёл. */
    static final int MAX_TRACKED = 10_000;

    /**
     * Что случилось с игроком между пульсами.
     *
     * @param level  новый уровень класса
     * @param points свободных очков талантов на момент события
     */
    record Change(int level, int points) {
    }

    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();

    /**
     * Сверяет уровень с прошлым наблюдением.
     *
     * @return повышение уровня или {@code null}, если его не было
     */
    Change observe(UUID player, int level, int points) {
        if (player == null) {
            return null;
        }
        int safe = Math.max(1, level);
        Integer previous = levels.put(player, safe);
        if (previous == null || safe <= previous) {
            trim();
            return null;
        }
        return new Change(safe, Math.max(0, points));
    }

    /** Запоминает уровень без сообщения (вход, смена класса, админский сброс). */
    void baseline(UUID player, int level) {
        if (player != null) {
            levels.put(player, Math.max(1, level));
            trim();
        }
    }

    /** Забывает игрока (выход с сервера). */
    void forget(UUID player) {
        if (player != null) {
            levels.remove(player);
        }
    }

    /** Сколько игроков помнится — для диагностики и тестов. */
    int tracked() {
        return levels.size();
    }

    /**
     * Держит карту в разумных рамках. Записи не истекают по времени: игрок,
     * который вернётся через месяц, не должен получить пачку «повышений»,
     * поэтому старые записи просто забываются — следующий пульс запомнит
     * текущий уровень молча.
     */
    private void trim() {
        if (levels.size() <= MAX_TRACKED) {
            return;
        }
        var iterator = levels.keySet().iterator();
        while (iterator.hasNext() && levels.size() > MAX_TRACKED) {
            iterator.next();
            iterator.remove();
        }
    }
}
