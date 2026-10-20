package ru.cherdakmd.chrdkstreams;

/**
 * Защита от подбора кода просмотра ({@code WATCH-XXXX}).
 *
 * <p>Код короткий и живёт в чате эфира, поэтому перебор «на удачу» нельзя
 * оставлять безнаказанным: после {@code max-attempts} ошибок ввод блокируется на
 * {@code lock-minutes}. Успешный ввод сбрасывает счётчик. Класс чистый — правило
 * закреплено тестами, а не проверяется вручную.</p>
 */
final class CodeGuard {

    /** Сколько ошибок подряд прощаем. {@code 0} — без ограничения. */
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    /** Длина блокировки по умолчанию. */
    static final long DEFAULT_LOCK_MILLIS = 5 * 60_000L;

    /** Счётчик ошибок и момент, до которого ввод запрещён. */
    record State(int failures, long lockedUntil) {

        static State empty() {
            return new State(0, 0L);
        }

        boolean locked(long nowMillis) {
            return nowMillis < lockedUntil;
        }

        /** Сколько ещё действует блокировка (0 — не заблокирован). */
        long lockRemainingMillis(long nowMillis) {
            return Math.max(0L, lockedUntil - nowMillis);
        }
    }

    private CodeGuard() {
    }

    /**
     * Зафиксировать ошибку.
     *
     * <p>При достижении порога счётчик сбрасывается, а ввод блокируется: иначе
     * после блокировки хватило бы ещё одной ошибки, чтобы продлить её бесконечно
     * «дешёвыми» попытками.</p>
     */
    static State failure(State state, int maxAttempts, long lockMillis, long nowMillis) {
        State current = state == null ? State.empty() : state;
        if (maxAttempts <= 0) {
            return new State(0, 0L);
        }
        int failures = Math.max(0, current.failures()) + 1;
        if (failures >= maxAttempts) {
            long until = nowMillis + Math.max(1L, lockMillis);
            return new State(0, until);
        }
        return new State(failures, current.lockedUntil());
    }

    /** Успешный ввод: счётчик и блокировка снимаются. */
    static State success() {
        return State.empty();
    }

    /** Сколько попыток осталось до блокировки (для подсказки). */
    static int attemptsLeft(State state, int maxAttempts) {
        if (maxAttempts <= 0) {
            return Integer.MAX_VALUE;
        }
        int failures = state == null ? 0 : Math.max(0, state.failures());
        return Math.max(0, maxAttempts - failures);
    }
}
