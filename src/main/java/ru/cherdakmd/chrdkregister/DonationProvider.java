package ru.cherdakmd.chrdkregister;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Единый интерфейс опроса внешних донатных сервисов (DonatePay, EasyDonate…).
 *
 * <p>Каждый провайдер умеет два запроса: «короткую голову» (последние N успешных —
 * для первичной инициализации курсора) и «всё после курсора». Идентификаторы платежей
 * уникальны только внутри своего сервиса, поэтому все хранилищные ключи снабжаются
 * префиксом {@link #key()} (например {@code ed:123}); исторические записи без
 * префикса трактуются как DonatePay.
 */
interface DonationProvider {

    /** Короткий идентификатор провайдера: donatepay, easydonate. */
    String key();

    /** Человекочитаемое имя для логов и сообщений админам. */
    String displayName();

    /** Настроен ли провайдер (есть токен и включён). !=true ⇒ опрос пропускается. */
    boolean ready();

    /** Обновить конфиг после /pass reload. */
    void updateConfig(PluginConfig config);

    /** Последние N успешных платежей в порядке возрастания id (для первичного курсора). */
    List<DonatePayClient.Transaction> fetchRecentSuccessful(int limit) throws IOException;

    /** Все успешные платежи с id строго больше курсора, по возрастанию id. */
    List<DonatePayClient.Transaction> fetchAfter(long cursorId, int limit) throws IOException;

    /** Общий разбор метки времени вида «2026-10-12 12:34:56» в миллисекунды эпохи. */
    static long parseServiceTimestamp(String raw, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException exception) {
            try {
                return java.time.Instant.parse(raw.trim()).toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return 0;
            }
        }
    }

    /** Безопасный BigDecimal из JsonElement (число или строка). */
    static BigDecimal parseAmountBigDecimal(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(element.getAsString().trim());
        } catch (RuntimeException exception) {
            return BigDecimal.ZERO;
        }
    }
}
