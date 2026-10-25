package ru.cherdakmd.chrdkregister.api;

/**
 * Бонусы действующего донатного ранга (API v10, 1.4.167).
 *
 * <p>Один объект вместо трёх отдельных вызовов: аддону почти всегда нужны все
 * числа сразу, а лишние обращения к ядру в горячем пути (добыча, крафт) дороже
 * одной записи. Все значения — проценты и целые прибавки, а не множители:
 * {@code 25} значит «+25 %», а не «в 25 раз».</p>
 *
 * <p><b>Потолки.</b> Ядро отдаёт ровно то, что записано в его конфиге
 * ({@code ranks.bonuses}). Старшая ступень ограничена 50 % опыта, 25 % добычи
 * и 10 дополнительными уровнями навыка — дальше таблица не растёт, даже если
 * администратор впишет больше: контракт важнее конфига, иначе витрина начнёт
 * продавать бесконечную силу.</p>
 *
 * @param level       ступень ранга, 0 — без ранга
 * @param xpPercent   прибавка к опыту профессий и сезонного пропуска, %
 * @param dropPercent прибавка к количеству добычи в находках и охоте, %
 * @param extraSkill  дополнительные уровни навыка сверх обычного потолка
 */
public record RankBonus(int level, int xpPercent, int dropPercent, int extraSkill) {

    /** Человек без ранга: нули, а не {@code null} — вызывающему коду не нужны проверки. */
    public static final RankBonus NONE = new RankBonus(0, 0, 0, 0);

    /** Пределы контракта: больше ядро не отдаёт, что бы ни было в конфиге. */
    public static final int MAX_XP_PERCENT = 50;
    public static final int MAX_DROP_PERCENT = 25;
    public static final int MAX_EXTRA_SKILL = 10;

    public RankBonus {
        level = Math.max(0, Math.min(5, level));
        xpPercent = Math.max(0, Math.min(MAX_XP_PERCENT, xpPercent));
        dropPercent = Math.max(0, Math.min(MAX_DROP_PERCENT, dropPercent));
        extraSkill = Math.max(0, Math.min(MAX_EXTRA_SKILL, extraSkill));
    }

    /** Множитель опыта для умножения наград: 1.0 без ранга, 1.5 на вершине. */
    public double xpFactor() {
        return 1.0 + xpPercent / 100.0;
    }

    /** Множитель добычи: 1.0 без ранга, 1.25 на вершине. */
    public double dropFactor() {
        return 1.0 + dropPercent / 100.0;
    }

    /** Ничего не даёт: аддон может пропустить свою ветку целиком. */
    public boolean empty() {
        return xpPercent == 0 && dropPercent == 0 && extraSkill == 0;
    }

    /**
     * Прибавка добычи к количеству предметов.
     *
     * <p>Округление вниз и хотя бы ноль: бонус не превращает одну находку
     * в две, он лишь иногда добавляет вторую — иначе выгоднее было бы дробить
     * добычу на мелкие стопки.</p>
     */
    public int extraDrops(int amount) {
        if (amount <= 0 || dropPercent <= 0) {
            return 0;
        }
        return (int) Math.floor(amount * dropPercent / 100.0);
    }
}
