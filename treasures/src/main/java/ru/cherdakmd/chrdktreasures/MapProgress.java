package ru.cherdakmd.chrdktreasures;

/**
 * Полоса раскопок клад-карты (1.4.180).
 *
 * <p>Карта раньше показывала только «осталось N блоков» и не менялась, пока
 * игрок копал: предмет обновлялся в метках, но лор оставался старым. Теперь
 * прогресс виден картинкой на десять знакомест — и на самой карте, и в
 * экшен-баре за каждый блок, и в списке карт, и в витрине.</p>
 *
 * <p>Класс чистый: ни Bukkit, ни сообщений — только арифметика полосы, поэтому
 * границы проверяются юнит-тестами. Полоса — подсказка, а не точное число:
 * точное количество блоков всегда стоит рядом.</p>
 */
public final class MapProgress {

    /** Сегментов в полосе. */
    public static final int SEGMENTS = 10;

    private MapProgress() {
    }

    /** Процент раскопок 0..100. Ноль нужного — ноль процентов, а не деление на ноль. */
    public static int percent(int dug, int needed) {
        if (needed <= 0) {
            return 0;
        }
        long value = Math.round(Math.max(0, dug) * 100.0 / needed);
        return (int) Math.clamp(value, 0, 100);
    }

    /** Сколько сегментов закрашено. */
    public static int filled(int percent) {
        int safe = Math.clamp(percent, 0, 100);
        return Math.clamp(Math.round(safe * SEGMENTS / 100.0f), 0, SEGMENTS);
    }

    /** Полоса без разметки — для логов, тестов и простого текста. */
    public static String bar(int percent) {
        int filled = filled(percent);
        return "█".repeat(filled) + "░".repeat(SEGMENTS - filled);
    }

    /** Полоса в MiniMessage: закрашенное золотое, пустое тёмное. */
    public static String colored(int percent) {
        int filled = filled(percent);
        return "<gold>" + "█".repeat(filled) + "</gold><dark_gray>"
                + "░".repeat(SEGMENTS - filled) + "</dark_gray>";
    }

    /** Полоса по счётчикам карты. */
    public static String colored(int dug, int needed) {
        return colored(percent(dug, needed));
    }
}
