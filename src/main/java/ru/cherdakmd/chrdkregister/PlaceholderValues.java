package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Чистые функции форматирования значений для плейсхолдеров PlaceholderAPI.
 * Вынесены без зависимости от самого PlaceholderAPI, чтобы покрываться юнит-тестами.
 */
final class PlaceholderValues {

    /** Текст для пустого значения (плейсхолдер вызван для оффлайн-игрока и т.п.). */
    static final String NONE = "нет";

    private PlaceholderValues() {
    }

    /** Короткое «да»/«нет» для бинарных плейсхолдеров (2FA, мут, регистрация). */
    static String yesNo(boolean value) {
        return value ? "да" : "нет";
    }

    /** Статус проходки: «активна», «истекла» или «нет проходки». */
    static String passStatus(RegistrationStore.PlayerAccount account, long nowMillis) {
        if (account == null || account.passUntil <= 0) return "нет проходки";
        return account.hasActivePass(nowMillis) ? "активна" : "истекла";
    }

    /**
     * Осталось целых дней проходки (неполный день считается за целый, чтобы в последние
     * сутки показывалось «1», а не «0»). Без проходки — 0.
     */
    static long wholeDaysLeft(long passUntilMillis, long nowMillis) {
        return wholeUnitsLeft(passUntilMillis, nowMillis, TimeUnit.DAYS.toMillis(1));
    }

    /** Осталось целых часов проходки (неполный час за целый). Без проходки — 0. */
    static long wholeHoursLeft(long passUntilMillis, long nowMillis) {
        return wholeUnitsLeft(passUntilMillis, nowMillis, TimeUnit.HOURS.toMillis(1));
    }

    private static long wholeUnitsLeft(long passUntilMillis, long nowMillis, long unitMillis) {
        long diff = passUntilMillis - nowMillis;
        if (diff <= 0) return 0;
        return (diff + unitMillis - 1) / unitMillis;
    }

    /** Остаток проходки строкой вида «2 дн. 3 ч.»; «истекла» / «нет проходки» по ситуации. */
    static String passLeft(RegistrationStore.PlayerAccount account, long nowMillis) {
        if (account == null || account.passUntil <= 0) return "нет проходки";
        return AccessManager.formatRemainingTime(account.passUntil, nowMillis);
    }

    /** Машиночитаемый id стадии авторизации: no_pass, needs_register, ..., authenticated. */
    static String stageId(AccessManager.PlayerStage stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }

    /** Короткое русское название стадии авторизации для таб-листа и скорбордов. */
    static String describeStage(AccessManager.PlayerStage stage) {
        return switch (stage) {
            case NO_PASS -> "нет проходки";
            case NEEDS_REGISTER -> "регистрация";
            case NEEDS_LOGIN -> "вход";
            case NEEDS_2FA -> "код 2FA";
            case NEEDS_2FA_SETUP -> "привязка 2FA";
            case AUTHENTICATED -> "в игре";
        };
    }

    /** Короткое название канала чата: «локальный» / «глобальный». */
    static String describeChannel(ChatConfig.Channel channel) {
        return channel == ChatConfig.Channel.GLOBAL ? "глобальный" : "локальный";
    }

    /** Машиночитаемый id канала чата: local / global. */
    static String channelId(ChatConfig.Channel channel) {
        return channel.name().toLowerCase(Locale.ROOT);
    }

    /** Активен ли мут игрока прямо сейчас. */
    static boolean isMuted(RegistrationStore.PlayerAccount account, long nowMillis) {
        return account != null
                && account.mute != null
                && account.mute.remainingMillis(nowMillis) > 0;
    }

    /**
     * Процент аккаунтов с активной проходкой от всех аккаунтов (как «конверсия» в {@code /pass stats}).
     * Пустой сервер (нет аккаунтов) — 0.
     */
    static int conversionPercent(int withPassAccounts, int totalAccounts) {
        if (withPassAccounts <= 0 || totalAccounts <= 0) return 0;
        return Math.round(100.0f * withPassAccounts / totalAccounts);
    }
}
