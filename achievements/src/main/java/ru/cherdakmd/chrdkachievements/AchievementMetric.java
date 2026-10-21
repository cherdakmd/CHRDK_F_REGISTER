package ru.cherdakmd.chrdkachievements;

import java.util.Locale;

/**
 * Счётчики, из которых складываются достижения.
 *
 * <p>Ид метрики — строковый («play-seconds», «mobs-killed»): он попадает в
 * `achievements.json` и в конфиг, поэтому переименование поля enum не должно
 * ломать сохранённый прогресс. {@link #byId(String)} принимает и ид, и имя
 * константы — старые файлы и ручные правки читаются одинаково.</p>
 */
public enum AchievementMetric {

    /** Секунды в игре (начисляются пульсом, пока игрок онлайн). */
    PLAY_SECONDS("play-seconds"),
    /** Число входов на сервер. */
    JOINS("joins"),
    /** Пройденные блоки (по горизонтали и вертикали, без телепортов). */
    DISTANCE_BLOCKS("distance-blocks"),
    /** Сломанные блоки. */
    BLOCKS_MINED("blocks-mined"),
    /** Алмазная руда и древние обломки. */
    ORES_MINED("ores-mined"),
    /** Убитые мобы (кроме боссов). */
    MOBS_KILLED("mobs-killed"),
    /** Дракон Края, Визер и Варден. */
    BOSSES_KILLED("bosses-killed"),
    /** Пойманная рыба (только успешный улов). */
    FISH_CAUGHT("fish-caught"),
    /** Скрафченные предметы. */
    ITEMS_CRAFTED("items-crafted"),
    /** Сообщения в общий и локальный чат. */
    CHAT_MESSAGES("chat-messages"),
    /** Действующая проходка (0/1) — опрашивается через API ядра. */
    PASS_ACTIVE("pass-active"),
    /** Аккаунт зарегистрирован (0/1). */
    REGISTERED("registered"),
    /** Привязана 2FA (0/1). */
    TWO_FACTOR("two-factor"),
    /** Служебная: сколько достижений уже открыто (для мета-достижения). */
    ACHIEVEMENTS("achievements");

    private final String id;

    AchievementMetric(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Строгий разбор ида; неизвестное значение — {@code null}. */
    public static AchievementMetric byId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AchievementMetric metric : values()) {
            if (metric.id.equals(normalized) || metric.name().equalsIgnoreCase(normalized)) {
                return metric;
            }
        }
        return null;
    }

    /** Счётчик «да/нет»: значение больше нуля считается выполненным. */
    public boolean flag() {
        return this == PASS_ACTIVE || this == REGISTERED || this == TWO_FACTOR;
    }
}
