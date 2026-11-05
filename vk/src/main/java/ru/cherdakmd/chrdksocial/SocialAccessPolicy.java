package ru.cherdakmd.chrdksocial;

import java.util.Locale;

/**
 * Чистая арифметика доступа в социальном плагине: без Bukkit, без состояния,
 * без сети — поэтому политика проверяется обычными JVM-тестами.
 *
 * <p>Гибридный переходный период считает <em>обе</em> даты и берёт более
 * позднюю: общий дедлайн от установки плагина (для уже игравших аккаунтов) и
 * личный срок от первого входа конкретного игрока (для новичков и тех, кто
 * вернулся после окончания общего периода). Так игрок, впервые зашедший на
 * пятнадцатый день, не оказывается в лобби без единого дня на привязку.</p>
 */
public final class SocialAccessPolicy {

    public static final long DAY_MILLIS = 86_400_000L;

    public static final String MODE_ANY_RANK = "any-rank";
    public static final String MODE_PRODUCT_LIST = "product-list";
    public static final String MODE_EITHER = "either";
    public static final String MODE_OFF = "off";

    private SocialAccessPolicy() {
    }

    /**
     * @param rolloutStartedAt момент первого запуска плагина (0 = сейчас)
     * @param firstSeenAt      момент первого входа игрока (0 = ещё не зафиксирован)
     * @param globalGraceDays  общий переходный период в днях
     * @param personalGraceDays личный срок в днях
     * @param now              текущее время, мс
     */
    public static long graceDeadline(long rolloutStartedAt, long firstSeenAt,
                                     int globalGraceDays, int personalGraceDays, long now) {
        long safeNow = now > 0L ? now : System.currentTimeMillis();
        long start = rolloutStartedAt > 0L ? rolloutStartedAt : safeNow;
        long global = start + Math.max(0, globalGraceDays) * DAY_MILLIS;
        long seen = firstSeenAt > 0L ? firstSeenAt : safeNow;
        long personal = seen + Math.max(0, personalGraceDays) * DAY_MILLIS;
        return Math.max(global, personal);
    }

    public static boolean graceActive(long deadline, long now) {
        return now < deadline;
    }

    /** Неизвестное значение выключает платный доступ, а не включает всё подряд. */
    public static String normalizeMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case MODE_ANY_RANK, MODE_PRODUCT_LIST, MODE_EITHER, MODE_OFF -> value;
            default -> MODE_OFF;
        };
    }

    public static boolean rankAllowed(String mode) {
        return MODE_ANY_RANK.equals(mode) || MODE_EITHER.equals(mode);
    }

    public static boolean productsAllowed(String mode) {
        return MODE_PRODUCT_LIST.equals(mode) || MODE_EITHER.equals(mode);
    }

    public static boolean modeActive(String mode) {
        return !MODE_OFF.equals(mode);
    }
}
