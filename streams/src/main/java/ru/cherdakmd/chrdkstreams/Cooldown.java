package ru.cherdakmd.chrdkstreams;

/**
 * Кулдаун команды: сколько ещё ждать до следующего вызова.
 *
 * <p>Чистая арифметика без состояния: момент последнего вызова хранит вызывающий
 * код. Так правило проверяется тестами, а не «на глаз» в игре. Нулевой
 * кулдаун означает «без ограничения» — это удобно для тестовых серверов.</p>
 */
final class Cooldown {

    private Cooldown() {
    }

    /**
     * Миллисекунд до следующего разрешённого вызова.
     *
     * @param lastMillis     когда команда выполнялась в прошлый раз; {@code 0} — ещё ни разу
     * @param cooldownMillis длина кулдауна; {@code <= 0} — ограничения нет
     * @param nowMillis      текущее время
     */
    static long remainingMillis(long lastMillis, long cooldownMillis, long nowMillis) {
        if (cooldownMillis <= 0L || lastMillis <= 0L) {
            return 0L;
        }
        long next = lastMillis + cooldownMillis;
        return Math.max(0L, next - nowMillis);
    }

    /** Секунды до следующего вызова, округлённые вверх (для сообщения игроку). */
    static long remainingSeconds(long lastMillis, long cooldownMillis, long nowMillis) {
        long millis = remainingMillis(lastMillis, cooldownMillis, nowMillis);
        return millis <= 0L ? 0L : (millis + 999L) / 1000L;
    }
}
