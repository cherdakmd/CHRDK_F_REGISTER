package ru.cherdakmd.chrdkregister;

/**
 * Решения сторожа подключения к DonatePay. Чистая логика — юнит-тестируема.
 *
 * <p>Принцип: опрос донатов и так бегает каждые `poll-seconds` — он же и является
 * проверкой связи. Сторож только анализирует счётчики: «N подряд неудач = падение»
 * (тревога один раз на эпизод), «первая удача после эпизода = восстановление».
 */
final class DonatePulse {

    private DonatePulse() {
    }

    /** Пора ли объявить «DonatePay недоступен» (только на переходе в отказ). */
    static boolean shouldAlertOutage(int failStreak, int threshold, boolean alreadyAlerted) {
        return !alreadyAlerted && failStreak >= Math.max(1, threshold);
    }

    /** Пора ли объявить восстановление (была тревога → пришла первая удача). */
    static boolean shouldAlertRecovery(boolean wasAlerted) {
        return wasAlerted;
    }

    /** Пора ли повторить тревогу (серия отказов всё тянется; 0 минут = без повторов). */
    static boolean repeatDue(long lastAlertAtMillis, long nowMillis, int repeatMinutes) {
        if (repeatMinutes <= 0) {
            return false;
        }
        return nowMillis - lastAlertAtMillis >= repeatMinutes * 60_000L;
    }

    /** Состояние связи для /pass health. */
    enum State {
        /** Первый удачный опрос был, серия отказов пуста. */
        OK,
        /** Уже обламывались, но успех был — связь шалит. */
        FLAKY,
        /** Ни одной удачной попытки с момента старта сервера. */
        NO_CONTACT
    }

    static State classify(long lastSuccessAtMillis, int failStreak) {
        if (lastSuccessAtMillis <= 0) {
            return failStreak > 0 ? State.NO_CONTACT : State.OK;
        }
        return failStreak > 0 ? State.FLAKY : State.OK;
    }

    /** Консольный heartbeat-пульс: тихо в норме (пустая строка), громко при проблеме. */
    static String heartbeatLine(long lastSuccessAtMillis, long nowMillis, int failStreak, String lastError) {
        if (failStreak > 0 && lastSuccessAtMillis <= 0) {
            return "DonatePay heartbeat: NO CONTACT — ни одной удачной попытки за запуск сервера"
                    + " (неудач подряд: " + failStreak + ")"
                    + ". Ошибка: " + (lastError == null ? "unknown" : lastError);
        }
        long agoSeconds = Math.max(0, (nowMillis - lastSuccessAtMillis) / 1000L);
        if (failStreak > 0) {
            return "DonatePay heartbeat: FLAKY — последний успех " + agoSeconds
                    + " с назад, неудач подряд: " + failStreak
                    + ". Ошибка: " + (lastError == null ? "unknown" : lastError);
        }
        return "DonatePay heartbeat: OK — последний успешный опрос " + agoSeconds + " с назад";
    }
}
