package ru.cherdakmd.chrdktreasures;

import java.util.Locale;

/**
 * Ранг кладоискателя — звание за число найденных кладов.
 *
 * <p>Ранг считается по общему числу находок, а не по собранной коллекции: даже
 * если игрок находит одни и те же обычные клады, путь до «Легенды» остаётся
 * честным и измеримым. Пороги подобраны под реальную скорость: обычный клад
 * выпадает примерно раз на сто блоков, поэтому 150 находок — это десятки
 * часов игры, а не вечер.</p>
 *
 * <p>Алмазы — награда за переход в ранг; её можно переопределить в
 * {@code config.yml} (секция {@code ranks}). Стартовый ранг награды не даёт.</p>
 */
public enum TreasureRank {

    NOVICE("novice", 0L, 0),
    SEEKER("seeker", 5L, 4),
    TREASURE_HUNTER("treasure_hunter", 15L, 10),
    CONNOISSEUR("connoisseur", 35L, 20),
    MASTER("master", 75L, 40),
    LEGEND("legend", 150L, 80);

    private final String id;
    private final long minFound;
    private final int diamonds;

    TreasureRank(String id, long minFound, int diamonds) {
        this.id = id;
        this.minFound = minFound;
        this.diamonds = diamonds;
    }

    public String id() {
        return id;
    }

    /** Сколько кладов нужно найти для этого ранга. */
    public long minFound() {
        return minFound;
    }

    /** Награда за переход в ранг по умолчанию. */
    public int diamonds() {
        return diamonds;
    }

    /** Ранг для числа находок: последний, порог которого уже достигнут. */
    public static TreasureRank of(long found) {
        TreasureRank rank = NOVICE;
        for (TreasureRank candidate : values()) {
            if (found >= candidate.minFound) {
                rank = candidate;
            }
        }
        return rank;
    }

    public static TreasureRank byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String needle = id.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (TreasureRank rank : values()) {
            if (rank.id.equals(needle)) {
                return rank;
            }
        }
        return null;
    }

    /** Следующий ранг или {@code null}, если игрок уже легенда. */
    public TreasureRank next() {
        int index = ordinal() + 1;
        return index < values().length ? values()[index] : null;
    }

    /** Сколько находок осталось до следующего ранга (0 — дальше некуда). */
    public long remaining(long found) {
        TreasureRank next = next();
        if (next == null) {
            return 0L;
        }
        return Math.max(0L, next.minFound - Math.max(found, minFound));
    }

    /**
     * Прогресс внутри текущего ранга: 0.0 в начале, 1.0 на пороге следующего.
     * У последнего ранга всегда 1.0 — он уже достигнут.
     */
    public double progress(long found) {
        TreasureRank next = next();
        if (next == null) {
            return 1.0;
        }
        long floor = minFound;
        long span = next.minFound - floor;
        if (span <= 0L) {
            return 1.0;
        }
        double value = (Math.max(found, floor) - floor) / (double) span;
        return Math.min(1.0, Math.max(0.0, value));
    }
}
