package ru.cherdakmd.chrdkevents;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Выбор следующего события по весам (1.4.160).
 *
 * <p>Алгоритм простой и предсказуемый: сумма весов подходящих событий, случайная
 * точка в этом отрезке, линейный проход. Событие на паузе (после недавнего
 * окончания) в сумму не входит — поэтому одно и то же не выпадает два раза
 * подряд, если у него есть {@code cooldown-seconds}.</p>
 *
 * <p>Класс чистый, {@link Random} передаётся снаружи: выбор проверяется тестом с
 * фиксированным зерном, а не «на удачу».</p>
 */
final class EventPicker {

    private EventPicker() {
    }

    /**
     * Выбирает событие.
     *
     * @param events    все события конфига
     * @param cooldowns до какого момента событие на паузе ({@code id → время})
     * @param now       текущее время в миллисекундах
     * @return выбранное событие или {@code null}, если все на паузе
     */
    static WorldEvent pick(List<WorldEvent> events, Map<String, Long> cooldowns,
                           long now, Random random) {
        if (events == null || events.isEmpty() || random == null) {
            return null;
        }
        Map<String, Long> pause = cooldowns == null ? Map.of() : cooldowns;
        long total = 0L;
        for (WorldEvent event : events) {
            if (!available(event, pause, now)) {
                continue;
            }
            total += event.weight();
        }
        if (total <= 0L) {
            return null;
        }
        long point = Math.floorMod(random.nextLong(), total);
        long walked = 0L;
        for (WorldEvent event : events) {
            if (!available(event, pause, now)) {
                continue;
            }
            walked += event.weight();
            if (point < walked) {
                return event;
            }
        }
        // Сюда попасть нельзя, но возвращаем последнее доступное, а не null.
        for (int index = events.size() - 1; index >= 0; index--) {
            if (available(events.get(index), pause, now)) {
                return events.get(index);
            }
        }
        return null;
    }

    private static boolean available(WorldEvent event, Map<String, Long> cooldowns, long now) {
        if (event == null || !event.usable()) {
            return false;
        }
        Long until = cooldowns.get(event.id());
        return until == null || until <= now;
    }

    /** Сколько событий сейчас доступно — для админской сводки. */
    static int availableCount(List<WorldEvent> events, Map<String, Long> cooldowns, long now) {
        if (events == null) {
            return 0;
        }
        int count = 0;
        for (WorldEvent event : events) {
            if (available(event, cooldowns, now)) {
                count++;
            }
        }
        return count;
    }
}
