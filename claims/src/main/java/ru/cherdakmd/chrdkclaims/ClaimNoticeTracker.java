package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Память уведомлений о границах приватов: последний чанк игрока и время
 * последних сообщений по каждому чанку. Без Bukkit — поведение покрыто тестами.
 *
 * <p>Кулдаун нужен из-за бега вдоль границы: без него игрок получает шквал
 * сообщений, когда чанк меняется туда-обратно несколько раз в секунду.
 * Память о последних приватах ограничена, поэтому карта не растёт бесконечно.</p>
 */
public final class ClaimNoticeTracker {

    /** Сколько последних приватов помнить для кулдауна (на игрока). */
    public static final int REMEMBERED = 6;

    /** Событие границы: какой приват покинули или на какой вошли. */
    public record Notice(boolean entering, ClaimNotice.Spot spot) {
    }

    private final Map<UUID, ClaimNotice.Spot> last = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, Long>> recent = new HashMap<>();

    /**
     * Обновляет состояние игрока и возвращает уведомления, которые нужно отправить.
     *
     * <p>Первый вызов после входа/телепорта даёт только «вход»: покидать игроку
     * нечего. Повторный вызов в том же чанке ничего не возвращает.</p>
     *
     * @param spot          снимок привата в новом чанке; {@code null} — игрок вне приватов
     * @param cooldownMillis минимальный интервал между сообщениями об одном чанке
     */
    public List<Notice> update(UUID playerId, long nowMillis, ClaimNotice.Spot spot,
                               long cooldownMillis) {
        if (playerId == null) {
            return List.of();
        }
        ClaimNotice.Spot previous = last.get(playerId);
        if (sameChunk(previous, spot)) {
            // Приват мог сменить владельца/цену, пока игрок стоит в чанке:
            // обновляем снимок, чтобы «выход» говорил актуальные данные.
            if (spot != null) {
                last.put(playerId, spot);
            }
            return List.of();
        }
        List<Notice> notices = new ArrayList<>(2);
        if (previous != null && !throttled(playerId, previous.where(), nowMillis, cooldownMillis)) {
            notices.add(new Notice(false, previous));
            remember(playerId, previous.where(), nowMillis);
        }
        if (spot != null) {
            if (!throttled(playerId, spot.where(), nowMillis, cooldownMillis)) {
                notices.add(new Notice(true, spot));
                remember(playerId, spot.where(), nowMillis);
            }
            last.put(playerId, spot);
        } else {
            last.remove(playerId);
        }
        return notices;
    }

    /**
     * Забывает последний чанк (смерть, телепорт, смена мира): следующий переход
     * не должен присылать «выход» из привата, в котором игрок уже не находится.
     * История кулдауна сохраняется, чтобы телепорт внутри привата не спамил.
     */
    public void forget(UUID playerId) {
        if (playerId != null) {
            last.remove(playerId);
        }
    }

    /** Полностью очищает состояние при выходе игрока с сервера. */
    public void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        last.remove(playerId);
        recent.remove(playerId);
    }

    /** Сколько игроков сейчас отслеживается (для диагностики). */
    public int tracked() {
        return last.size();
    }

    private static boolean sameChunk(ClaimNotice.Spot left, ClaimNotice.Spot right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.x() == right.x() && left.z() == right.z()
                && left.world().equals(right.world());
    }

    private boolean throttled(UUID playerId, String where, long nowMillis, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return false;
        }
        Map<String, Long> seen = recent.get(playerId);
        if (seen == null) {
            return false;
        }
        Long at = seen.get(where);
        return at != null && nowMillis >= at && nowMillis - at < cooldownMillis;
    }

    private void remember(UUID playerId, String where, long nowMillis) {
        LinkedHashMap<String, Long> seen =
                recent.computeIfAbsent(playerId, key -> new LinkedHashMap<>());
        // Переставляем запись в конец: вытесняются давно не использованные чанки.
        seen.remove(where);
        seen.put(where, nowMillis);
        while (seen.size() > REMEMBERED) {
            Iterator<String> eldest = seen.keySet().iterator();
            if (!eldest.hasNext()) {
                break;
            }
            eldest.next();
            eldest.remove();
        }
    }
}
