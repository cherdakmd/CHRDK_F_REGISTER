package ru.cherdakmd.chrdkquests;

import java.util.Locale;

/**
 * Вид задания (1.4.161).
 *
 * <p>Тип решает не только текст, но и когда вообще двигается прогресс:
 * {@link #KILL} и {@link #MINE} считаются в момент события, а {@link #GATHER}
 * не считает ничего — предметы нужно принести и сдать. Поэтому «собери 16
 * костей» нельзя выполнить, просто постояв на ферме: сдача забирает вещи из
 * инвентаря.</p>
 */
public enum QuestType {

    /** Убить существ из списка. */
    KILL,
    /** Сломать блоки из списка. */
    MINE,
    /** Принести и сдать предметы из списка. */
    GATHER;

    /** Разбирает вид задания; регистр и пробелы не важны. */
    public static QuestType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Ид вида для конфига, команд и плейсхолдеров. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
