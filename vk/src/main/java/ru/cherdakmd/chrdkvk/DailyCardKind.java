package ru.cherdakmd.chrdkvk;

import java.util.Locale;

/**
 * Четыре персональные номинации суток. Вместе с {@code wall-digest} в 21:00
 * это ровно пять записей на стене сообщества за день.
 *
 * <p>Порядок объявления — порядок публикации в течение дня. Часы лежат здесь
 * только как дефолты: владелец может переназначить их в {@code config.yml}.</p>
 */
public enum DailyCardKind {

    /** Баланс алмазного счёта по всем известным серверу игрокам. */
    RICHEST("richest", "САМЫЙ БОГАТЫЙ", "Самый богатый игрок дня", 9),
    /** Максимальная ступень престижа из снимка CHRDK_Skills. */
    PRESTIGE("prestige", "САМЫЙ ПРОКАЧАННЫЙ", "Самый прокачанный игрок дня", 12),
    /** Больше всего добытых блоков за сутки; алмазы идут вторым числом. */
    MINER("miner", "ШАХТЁР ДНЯ", "Шахтёр дня", 15),
    /** Больше всего побед над враждебными мобами за сутки. */
    HUNTER("hunter", "ОХОТНИК ДНЯ", "Охотник дня", 18);

    private final String id;
    private final String badge;
    private final String title;
    private final int defaultHour;

    DailyCardKind(String id, String badge, String title, int defaultHour) {
        this.id = id;
        this.badge = badge;
        this.title = title;
        this.defaultHour = defaultHour;
    }

    /** Ключ в ledger и в {@code guid} записи ВК. */
    public String id() {
        return id;
    }

    /** Короткая надпись на бейдже постера. */
    public String badge() {
        return badge;
    }

    /** Человекочитаемое название номинации для текста записи. */
    public String title() {
        return title;
    }

    public int defaultHour() {
        return defaultHour;
    }

    /** Ключ часа в {@code config.yml}: {@code wall-daily-cards.richest-hour}. */
    public String hourKey() {
        return id + "-hour";
    }

    public static DailyCardKind byId(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (DailyCardKind kind : values()) {
            if (kind.id.equals(needle)) {
                return kind;
            }
        }
        return null;
    }
}
