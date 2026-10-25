package ru.cherdakmd.chrdkregister;

/**
 * Чистая арифметика сроков покупок: продление с потолком и оформление ника.
 *
 * <p>Все методы — без Bukkit и без состояния, поэтому проверяются юнит-тестами. Логика
 * важная: именно здесь решается, что повторная покупка продлевает срок, но не превращает
 * «30 дней» в «тридцать лет».</p>
 */
public final class ShopEntitlements {

    public static final long DAY_MILLIS = 86_400_000L;

    private ShopEntitlements() {
    }

    /**
     * Новый срок товара после покупки.
     *
     * <p>Повторная покупка добавляет дни к текущему сроку (а не обнуляет его), однако
     * суммарный срок не может уйти дальше {@code now + maxTotalDays}. Если старый срок уже
     * истёк, покупка начинается с текущего момента.</p>
     *
     * @param nowMillis     текущее время, мс
     * @param currentUntil  активный срок до покупки (0 — покупок ещё не было)
     * @param days          сколько дней куплено
     * @param maxTotalDays  потолок суммарного срока в днях
     * @return момент окончания после покупки
     */
    public static long extend(long nowMillis, long currentUntil, int days, int maxTotalDays) {
        int safeDays = Math.max(0, days);
        int cap = Math.max(1, maxTotalDays);
        long base = Math.max(nowMillis, currentUntil);
        long ceiling = nowMillis + cap * DAY_MILLIS;
        long target = base + safeDays * DAY_MILLIS;
        return Math.min(target, ceiling);
    }

    /** Товар ещё действует. */
    public static boolean active(long untilMillis, long nowMillis) {
        return untilMillis > nowMillis;
    }

    /** Остаток в днях с округлением вверх: 1 час — это ещё «1 дн.». */
    public static int remainingDays(long untilMillis, long nowMillis) {
        if (untilMillis <= nowMillis) {
            return 0;
        }
        long remaining = untilMillis - nowMillis;
        return (int) Math.max(1, (remaining + DAY_MILLIS - 1) / DAY_MILLIS);
    }

    /**
     * Оборачивает ник в разметку товара: {@code <gold>Nick</gold>}.
     *
     * <p>Закрывающие теги строятся по всем открытым тегам в обратном порядке, поэтому
     * корректно закрываются и {@code <gradient:#ffcc00:#ff8800>}, и вложенные
     * {@code <bold><gold>}. Если разметка битая (нет {@code >}), ник возвращается как есть:
     * лучше без цвета, чем с мусором в чате.</p>
     */
    public static String decorate(String playerName, String tag) {
        String name = playerName == null ? "" : playerName;
        if (tag == null || tag.isBlank()) {
            return name;
        }
        if (tag.indexOf('<') < 0) {
            return tag + name; // обычный текстовый префикс: пробелы значимы
        }
        String trimmed = tag.trim();
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        int index = 0;
        while (index < trimmed.length()) {
            int start = trimmed.indexOf('<', index);
            if (start < 0) {
                break;
            }
            int end = trimmed.indexOf('>', start);
            if (end < 0) {
                break;
            }
            String inside = trimmed.substring(start + 1, end).trim();
            if (inside.startsWith("/")) {
                if (!open.isEmpty()) {
                    open.pop();
                }
            } else if (!inside.isEmpty()) {
                int colon = inside.indexOf(':');
                open.push(colon >= 0 ? inside.substring(0, colon).trim() : inside);
            }
            index = end + 1;
        }
        if (open.isEmpty()) {
            return name; // открывающих тегов нет — не подставляем обрывки разметки
        }
        StringBuilder closing = new StringBuilder();
        while (!open.isEmpty()) {
            closing.append("</").append(open.pop()).append('>');
        }
        return trimmed + name + closing;
    }
}
