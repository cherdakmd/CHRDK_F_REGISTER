package ru.cherdakmd.chrdktierforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Анти-спам подсказок наковальни (1.4.80).
 *
 * <p>{@code PrepareAnvilEvent} приходит на каждое движение в окне — включая каждую
 * букву переименования, — поэтому «нужен предмет того же тира» грозило двадцатью
 * одинаковыми строками в секунду. Гейт помнит последнюю сигнатуру на игрока и
 * пропускает её повтор только один раз; успешная операция (или смена пары)
 * сбрасывает память, чтобы следующая ошибка снова была слышна.
 *
 * <p>Чистая логика без Bukkit — покрыта юнит-тестами.
 */
final class AnvilNoticeGate {

    private final Map<UUID, String> last = new HashMap<>();

    /** true — такую подсказку ещё не показывали (и запоминаем её как последнюю). */
    synchronized boolean shouldNotify(UUID player, String signature) {
        if (player == null || signature == null) {
            return true;
        }
        return !signature.equals(last.put(player, signature));
    }

    /** Сброс после успешной операции: следующая ошибка снова должна прозвучать. */
    synchronized void reset(UUID player) {
        last.remove(player);
    }

    /** Выход игрока — память не держим. */
    synchronized void clear(UUID player) {
        last.remove(player);
    }
}
