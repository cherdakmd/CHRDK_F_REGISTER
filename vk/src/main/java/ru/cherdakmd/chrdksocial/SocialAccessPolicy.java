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

    /* ------------------------- счётчики для TAB (plain text) ------------------------- */

    /** Остаток срока товара: «27 дн.», «нет» или «∞» для бессрочного доступа. */
    public static String accessCounter(long untilMillis, long now, boolean permanent) {
        if (permanent) return "∞";
        if (untilMillis <= now) return "нет";
        return remainingDays(untilMillis, now) + " дн.";
    }

    /**
     * Персональный статус привязки: «ВК» / «MAX», либо сколько дней осталось
     * до обязательной привязки. Возвращает обычный текст — PAPI не понимает MiniMessage.
     */
    public static String providerLabel(String provider) {
        if (provider == null || provider.isBlank()) return "привязан";
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "vk" -> "ВК";
            case "max" -> "MAX";
            default -> "привязан";
        };
    }

    /** Полное название мессенджера для понятной строки TAB. */
    public static String providerTitle(String provider) {
        return switch (provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT)) {
            case "vk" -> "ВКонтакте";
            case "max" -> "MAX";
            default -> "мессенджер";
        };
    }

    public static String linkCounter(String provider, long graceDeadline, long now,
                                     boolean required, boolean emergency) {
        if (provider != null && !provider.isBlank()) return providerLabel(provider);
        if (!required) return "не требуется";
        if (emergency) return "аварийный вход";
        if (graceDeadline <= now) return "обязательна";
        return "осталось " + remainingDays(graceDeadline, now) + " дн.";
    }

    /**
     * Готовая строка для TAB: игрок должен увидеть не безликое «14 дн.», а явный
     * выбор — привязан, купил проход, либо «БЕЗ ПРИВЯЗКИ» со сроком до закрытия.
     */
    public static String tabStatus(boolean linked, String provider, long accessUntil,
                                   long graceDeadline, long now, boolean required,
                                   boolean emergency, boolean permanent) {
        if (linked) return "✅ привязан (" + providerLabel(provider) + ")";
        if (permanent || accessUntil > now) {
            return "✅ проход: " + accessCounter(accessUntil, now, permanent);
        }
        if (!required) return "✅ привязка не требуется";
        if (emergency) return "⚠ аварийный вход";
        if (graceDeadline <= now) return "⛔ ВХОД ЗАКРЫТ";
        return "⚠ БЕЗ ПРИВЯЗКИ: осталось " + remainingDays(graceDeadline, now) + " дн.";
    }

    /**
     * Одна строка для TAB: понятный выбор и следующий шаг, а не два счётчика
     * подряд. Возвращает MiniMessage, потому что PAPI подставляет значение до
     * рендера шаблона CHRDK_Core. После привязки строка не содержит ни срока,
     * ни дедлайна — счётчик исчезает полностью.
     */
    public static String tabLine(boolean linked, String provider, long accessUntil,
                                 long graceDeadline, long now, boolean required,
                                 boolean emergency, boolean permanent) {
        if (linked) {
            return "<green>✅ " + providerTitle(provider) + " привязан — можно играть</green>";
        }
        if (permanent || accessUntil > now) {
            return "<green>✅ Вход без ВК оплачен: " + accessCounter(accessUntil, now, permanent)
                    + " · привязка не нужна</green>";
        }
        if (!required) return "<green>✅ Привязка не требуется — можно играть</green>";
        if (emergency) return "<yellow>⚠ Аварийный вход: привяжите ВК через /link</yellow>";
        if (graceDeadline <= now) {
            return "<red>⛔ Вход закрыт: /link → код → бот ВКонтакте</red>";
        }
        return "<yellow>⚠ Привяжите ВКонтакте: /link → код боту. Осталось "
                + remainingDays(graceDeadline, now) + " дн.</yellow>";
    }

    /** Остаток дней с округлением вверх: 1 час — это ещё «1 дн.». */
    public static int remainingDays(long untilMillis, long now) {
        long left = untilMillis - now;
        if (left <= 0L) return 0;
        return (int) Math.max(1, (left + DAY_MILLIS - 1) / DAY_MILLIS);
    }
}
