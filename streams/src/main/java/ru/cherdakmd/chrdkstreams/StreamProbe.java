package ru.cherdakmd.chrdkstreams;

/**
 * Итог опроса всех каналов одной привязки.
 *
 * <p>С 1.4.121 у стримера может быть несколько каналов (по одному на площадку).
 * Эфир считается идущим, если жив <b>хотя бы один</b> канал, а зрители
 * <b>суммируются</b>: два одновременных эфира по 3 зрителя проходят порог
 * {@code min-viewers: 5}. При этом минуты кредитуются один раз на игрока —
 * сумма зрителей влияет только на порог, но не на скорость награды.</p>
 *
 * <p>Класс чистый (без Bukkit), чтобы правило было закреплено юнит-тестами.</p>
 *
 * @param live    жив ли хотя бы один канал
 * @param viewers сумма зрителей по живым каналам
 */
record StreamProbe(boolean live, long viewers) {

    /** Ни одного живого канала. */
    static final StreamProbe OFFLINE = new StreamProbe(false, 0L);

    StreamProbe {
        viewers = Math.max(0L, viewers);
    }

    /** Учесть ответ площадки: офлайн и {@code null} ничего не меняют. */
    StreamProbe plus(LiveStream stream) {
        if (stream == null || !stream.live()) {
            return this;
        }
        return new StreamProbe(true, viewers + Math.max(0, stream.viewers()));
    }

    /** Проходит ли эфир порог зрителей (порог 0 — любой живой канал). */
    boolean passes(int minViewers) {
        return live && viewers >= Math.max(0, minViewers);
    }
}
