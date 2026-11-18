package ru.cherdakmd.chrdkanticheat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Дедупликация алертов: не чаще одного на пару «игрок+чек» за окно секунд.
 *
 * <p>В журнал пишется каждый флаг — троттлинг ограничивает только шум в чате
 * модераторов. Ключи сравниваются без учёта регистра.</p>
 */
final class AlertThrottle {

    private final long windowMillis;
    private final Map<String, Long> lastAlert = new HashMap<>();

    AlertThrottle(long windowMillis) {
        if (windowMillis < 1) {
            throw new IllegalArgumentException("окно дедупликации должно быть положительным");
        }
        this.windowMillis = windowMillis;
    }

    /**
     * @return {@code true}, если алерт сейчас отправлять можно (и запоминает момент)
     */
    synchronized boolean shouldAlert(String player, String check, long nowMillis) {
        String key = key(player, check);
        Long previous = lastAlert.get(key);
        if (previous != null && nowMillis - previous < windowMillis) {
            return false;
        }
        lastAlert.put(key, nowMillis);
        if (lastAlert.size() > 1000) {
            lastAlert.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= windowMillis);
        }
        return true;
    }

    private static String key(String player, String check) {
        return player.toLowerCase(Locale.ROOT) + "|" + check.toLowerCase(Locale.ROOT);
    }
}
