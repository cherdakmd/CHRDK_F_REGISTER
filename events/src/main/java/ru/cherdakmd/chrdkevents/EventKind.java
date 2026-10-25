package ru.cherdakmd.chrdkevents;

import java.util.Locale;

/**
 * Вид мирового события (1.4.160).
 *
 * <p>Вид определяет, какую именно «мягкую» выгоду получает сервер: надбавку к
 * опыту, лишний кусок руды, лишний дроп с мобов или временный эффект. Ни один
 * вид не выдаёт монеты, алмазы или силу напрямую — событие должно собирать
 * людей, а не заменять экономику.</p>
 */
public enum EventKind {

    /** Надбавка к опыту за любое дело. */
    XP,
    /** Шанс лишнего куска руды при добыче. */
    MINING,
    /** Шанс лишнего дропа с мобов. */
    LOOT,
    /** Временные эффекты всем игрокам. */
    EFFECT;

    /** Разбирает вид из конфига; регистр и пробелы не важны. */
    public static EventKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Ид вида для плейсхолдеров и статистики. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
