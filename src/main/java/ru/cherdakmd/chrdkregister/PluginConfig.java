package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Holds validated configuration settings for the Purpur registration, DonatePay pass & TOTP 2FA plugin. */
public final class PluginConfig {
    /** A pass tier: a donation sum grants a number of days. */
    public record PassTier(int amountRubles, int days, String label) {
    }

    private final String donatePayApiToken;
    private final String donatePayPaymentUrl;
    private final int minimumDonationRubles;
    private final int donatePollSeconds;
    private final int donateHealthSeconds;
    private final int donateAlertFailStreak;
    private final boolean donateAlertAdmins;
    private final int donateAlertRepeatMinutes;
    private final int donatePendingNotifyHours;
    private final boolean easyDonateEnabled;
    private final String easyDonateShopKey;
    private final String easyDonatePaymentUrl;

    /** Подписочный провайдер Boosty (сеттится из load(), в большой ctor не входит). */
    private BoostySettings boosty = BoostySettings.DISABLED;
    private TradeMcSettings trademc = TradeMcSettings.DISABLED;

    /** Динамический MOTD (сеттится из load(), как и Boosty). */
    private MotdSettings motd = MotdSettings.DISABLED;

    /** Мастер-флаг донат-стены проходки (seed из load()). */
    private boolean passEnabled = true;

    /** donation-actions: консольные команды по входящим донатам (не часть ctor — как boosty/motd). */
    private List<DonateAction> donateActions = List.of();

    /** donatepay.show-balance (не часть ctor): писать ли имя кошелька и баланс в boot-лог. */
    private boolean donatePayShowBalance = true;

    /** server-menu.* (не часть ctor): конфиг-driven серверное меню /menu. */
    private ServerMenuSettings serverMenu = ServerMenuSettings.DISABLED;
    private final boolean donatePayEnabled;
    private final boolean strictCaseMatch;
    private final boolean donateReviewUnmatched;
    private final boolean donateFuzzyMatch;
    private final boolean paymentCodeEnabled;
    private final int paymentCodeDigits;
    private final int donationJournalKeepDays;

    private final int grantDays;
    private final List<PassTier> passTiers;
    private final List<Long> expiryWarningMinutes;
    private final boolean expiryBossBar;
    private final boolean expiryWarningSound;
    private final int kickAfterSeconds;
    private final boolean passGuiMenu;
    private final String passGroup;
    private final boolean passGroupRemoveExpired;

    private final int sessionMinutes;
    private final int minPasswordLength;
    private final int maxPasswordLength;
    private final int maxLoginAttempts;
    private final int reminderSeconds;
    private final int captchaAfterAttempts;
    private final boolean authDialogs;
    private final boolean buttonLogin;
    private final String buttonLoginSymbols;
    private final boolean maskCommandsInLogs;
    private final boolean sessionSkips2fa;
    private final boolean sessionRequireSameUuid;
    private final boolean rejectCommonPasswords;
    private final boolean rejectNicknameAsPassword;
    private final boolean passwordStrengthHint;

    private final List<Integer> backoffSeconds;
    private final int accountMaxAttempts;
    private final int accountWindowMinutes;
    private final int perIpMaxAttempts;
    private final int perIpWindowMinutes;
    private final int tempIpBanMinutes;
    private final boolean bruteForceNotifyAdmins;

    private final boolean totpEnabled;
    private final String totpIssuer;
    private final int totpWindowSteps;
    private final int totpMaxAttempts;
    private final boolean totpQrMap;

    private final boolean antiMultiAltEnabled;
    private final String antiMultiAltMode;
    private final int maxAccountsPerIp;
    private final int antiMultiAltWindowMinutes;

    private final int storageSaveDebounceSeconds;
    private final boolean storageWriteAsync;
    private final int storageBackupCount;
    private final boolean storageBackupOnStart;

    private final boolean auditEnabled;
    private final int auditKeepDays;
    private final boolean auditLogLogins;

    private final String discordWebhookUrl;
    private final List<String> notificationEvents;
    private final List<String> notificationMentions;

    private final boolean bStatsEnabled;

    private final ZoneId timeZone;

    private final ChatConfig chat;

    public PluginConfig(
            String donatePayApiToken,
            String donatePayPaymentUrl,
            int minimumDonationRubles,
            int donatePollSeconds,
            boolean strictCaseMatch,
            int grantDays,
            int sessionMinutes,
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            int reminderSeconds
    ) {
        this(
                donatePayApiToken, donatePayPaymentUrl, minimumDonationRubles, donatePollSeconds, strictCaseMatch,
                true, true, false, 4, 365,
                grantDays, defaultTiers(grantDays), List.of(10_080L, 4_320L, 1_440L, 60L), true, true, 0, true, "", true,
                sessionMinutes, minPasswordLength, maxPasswordLength, maxLoginAttempts, reminderSeconds, 3,
                false, true, "!@#$%&*-_+=.?", true, false, true, false, false, true,
                List.of(0, 0, 1, 2, 5, 10, 30, 60, 120, 300), maxLoginAttempts, 10, 15, 10, 15, true,
                true, "CHRDK", 1, maxLoginAttempts, true,
                true, "notify", 3, 240,
                5, true, 14, true,
                true, 180, true,
                "", List.of("pass.granted", "pass.revoked", "bruteforce.alert", "totp.reset",
                        "donatepay.error", "donatepay.down", "donatepay.up"),
                List.of(),
                true,
                ZoneId.of("Europe/Moscow"),
                new ChatConfig(),
                300, 10, true, 15, 24, false, "", "", true
        );
    }

    public PluginConfig(
            String donatePayApiToken,
            String donatePayPaymentUrl,
            int minimumDonationRubles,
            int donatePollSeconds,
            boolean strictCaseMatch,
            boolean donateReviewUnmatched,
            boolean donateFuzzyMatch,
            boolean paymentCodeEnabled,
            int paymentCodeDigits,
            int donationJournalKeepDays,
            int grantDays,
            List<PassTier> passTiers,
            List<Long> expiryWarningMinutes,
            boolean expiryBossBar,
            boolean expiryWarningSound,
            int kickAfterSeconds,
            boolean passGuiMenu,
            String passGroup,
            boolean passGroupRemoveExpired,
            int sessionMinutes,
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            int reminderSeconds,
            int captchaAfterAttempts,
            boolean authDialogs,
            boolean buttonLogin,
            String buttonLoginSymbols,
            boolean maskCommandsInLogs,
            boolean sessionSkips2fa,
            boolean sessionRequireSameUuid,
            boolean rejectCommonPasswords,
            boolean rejectNicknameAsPassword,
            boolean passwordStrengthHint,
            List<Integer> backoffSeconds,
            int accountMaxAttempts,
            int accountWindowMinutes,
            int perIpMaxAttempts,
            int perIpWindowMinutes,
            int tempIpBanMinutes,
            boolean bruteForceNotifyAdmins,
            boolean totpEnabled,
            String totpIssuer,
            int totpWindowSteps,
            int totpMaxAttempts,
            boolean totpQrMap,
            boolean antiMultiAltEnabled,
            String antiMultiAltMode,
            int maxAccountsPerIp,
            int antiMultiAltWindowMinutes,
            int storageSaveDebounceSeconds,
            boolean storageWriteAsync,
            int storageBackupCount,
            boolean storageBackupOnStart,
            boolean auditEnabled,
            int auditKeepDays,
            boolean auditLogLogins,
            String discordWebhookUrl,
            List<String> notificationEvents,
            List<String> notificationMentions,
            boolean bStatsEnabled,
            ZoneId timeZone,
            ChatConfig chat,
            int donateHealthSeconds,
            int donateAlertFailStreak,
            boolean donateAlertAdmins,
            int donateAlertRepeatMinutes,
            int donatePendingNotifyHours,
            boolean easyDonateEnabled,
            String easyDonateShopKey,
            String easyDonatePaymentUrl,
            boolean donatePayEnabled
    ) {
        this.donatePayApiToken = donatePayApiToken == null ? "" : donatePayApiToken.trim();
        this.donatePayPaymentUrl = sanitizeUrl(donatePayPaymentUrl);
        this.minimumDonationRubles = Math.max(1, minimumDonationRubles);
        this.donatePollSeconds = Math.max(15, donatePollSeconds);
        this.donateHealthSeconds = Math.clamp(donateHealthSeconds, 60, 3600);
        this.donateAlertFailStreak = Math.clamp(donateAlertFailStreak, 3, 100);
        this.donateAlertAdmins = donateAlertAdmins;
        this.donateAlertRepeatMinutes = Math.clamp(donateAlertRepeatMinutes, 0, 1440);
        this.donatePendingNotifyHours = Math.clamp(donatePendingNotifyHours, 0, 8760);
        this.easyDonateEnabled = easyDonateEnabled;
        this.easyDonateShopKey = easyDonateShopKey == null ? "" : easyDonateShopKey.trim();
        this.easyDonatePaymentUrl = sanitizeUrl(easyDonatePaymentUrl);
        this.donatePayEnabled = donatePayEnabled;
        this.strictCaseMatch = strictCaseMatch;
        this.donateReviewUnmatched = donateReviewUnmatched;
        this.donateFuzzyMatch = donateFuzzyMatch;
        this.paymentCodeEnabled = paymentCodeEnabled;
        this.paymentCodeDigits = Math.clamp(paymentCodeDigits, 3, 8);
        this.donationJournalKeepDays = Math.clamp(donationJournalKeepDays, 1, 3650);

        this.grantDays = Math.clamp(grantDays, 1, 3650);
        this.passTiers = normalizeTiers(passTiers, this.grantDays);
        List<Long> warnings = sanitizeWarnings(expiryWarningMinutes);
        this.expiryWarningMinutes = warnings;
        this.expiryBossBar = expiryBossBar;
        this.expiryWarningSound = expiryWarningSound;
        this.kickAfterSeconds = Math.clamp(kickAfterSeconds, 0, 3600);
        this.passGuiMenu = passGuiMenu;
        this.passGroup = passGroup == null ? "" : passGroup.trim();
        this.passGroupRemoveExpired = passGroupRemoveExpired;

        this.sessionMinutes = Math.clamp(sessionMinutes, 0, 10080);
        this.minPasswordLength = Math.clamp(minPasswordLength, 4, 32);
        this.maxPasswordLength = Math.max(this.minPasswordLength, Math.min(128, maxPasswordLength));
        this.maxLoginAttempts = Math.clamp(maxLoginAttempts, 1, 20);
        this.reminderSeconds = Math.clamp(reminderSeconds, 2, 120);
        this.captchaAfterAttempts = Math.clamp(captchaAfterAttempts, 0, 10);
        this.authDialogs = authDialogs;
        this.buttonLogin = buttonLogin;
        this.buttonLoginSymbols = buttonLoginSymbols == null ? "" : buttonLoginSymbols.trim();
        this.maskCommandsInLogs = maskCommandsInLogs;
        this.sessionSkips2fa = sessionSkips2fa;
        this.sessionRequireSameUuid = sessionRequireSameUuid;
        this.rejectCommonPasswords = rejectCommonPasswords;
        this.rejectNicknameAsPassword = rejectNicknameAsPassword;
        this.passwordStrengthHint = passwordStrengthHint;

        List<Integer> backoff = new ArrayList<>();
        if (backoffSeconds != null) {
            for (Integer step : backoffSeconds) {
                if (step != null && step >= 0) backoff.add(Math.min(step, 3600));
            }
        }
        this.backoffSeconds = backoff.isEmpty() ? List.of(0, 0, 1, 2, 5, 10, 30, 60, 120, 300) : List.copyOf(backoff);
        this.accountMaxAttempts = Math.clamp(accountMaxAttempts, 1, 100);
        this.accountWindowMinutes = Math.clamp(accountWindowMinutes, 1, 1440);
        this.perIpMaxAttempts = Math.clamp(perIpMaxAttempts, 1, 500);
        this.perIpWindowMinutes = Math.clamp(perIpWindowMinutes, 1, 1440);
        this.tempIpBanMinutes = Math.clamp(tempIpBanMinutes, 0, 10080);
        this.bruteForceNotifyAdmins = bruteForceNotifyAdmins;

        this.totpEnabled = totpEnabled;
        this.totpIssuer = (totpIssuer == null || totpIssuer.isBlank()) ? "CHRDK" : totpIssuer.trim();
        this.totpWindowSteps = Math.clamp(totpWindowSteps, 0, 10);
        this.totpMaxAttempts = Math.clamp(totpMaxAttempts, 1, 20);
        this.totpQrMap = totpQrMap;

        this.antiMultiAltEnabled = antiMultiAltEnabled;
        String mode = antiMultiAltMode == null ? "notify" : antiMultiAltMode.trim().toLowerCase(Locale.ROOT);
        this.antiMultiAltMode = mode.equals("deny") ? "deny" : "notify";
        this.maxAccountsPerIp = Math.clamp(maxAccountsPerIp, 1, 100);
        this.antiMultiAltWindowMinutes = Math.clamp(antiMultiAltWindowMinutes, 1, 10080);

        this.storageSaveDebounceSeconds = Math.clamp(storageSaveDebounceSeconds, 1, 600);
        this.storageWriteAsync = storageWriteAsync;
        this.storageBackupCount = Math.clamp(storageBackupCount, 0, 500);
        this.storageBackupOnStart = storageBackupOnStart;

        this.auditEnabled = auditEnabled;
        this.auditKeepDays = Math.clamp(auditKeepDays, 1, 3650);
        this.auditLogLogins = auditLogLogins;

        this.discordWebhookUrl = sanitizeUrl(discordWebhookUrl);
        this.notificationEvents = notificationEvents == null ? List.of() : List.copyOf(notificationEvents);
        this.notificationMentions = notificationMentions == null ? List.of() : List.copyOf(notificationMentions);

        this.bStatsEnabled = bStatsEnabled;
        this.timeZone = timeZone == null ? ZoneId.of("Europe/Moscow") : timeZone;
        this.chat = chat == null ? new ChatConfig() : chat;
    }

    public static PluginConfig fromBukkitConfig(FileConfiguration config) {
        int maxLoginAttempts = config.getInt("auth.max-login-attempts", 5);
        List<PassTier> tiers = parseTiers(config.getMapList("pass.tiers"));
        ZoneId zone = parseZone(config.getString("general.timezone", "Europe/Moscow"));
        PluginConfig parsed = new PluginConfig(
                config.getString("donatepay.api-token", ""),
                config.getString("donatepay.payment-url", ""),
                config.getInt("donatepay.minimum-rubles", 150),
                config.getInt("donatepay.poll-seconds", 30),
                config.getBoolean("donatepay.strict-case-match", false),
                config.getBoolean("donatepay.review-unmatched", true),
                config.getBoolean("donatepay.fuzzy-match", true),
                config.getBoolean("donatepay.payment-code", false),
                config.getInt("donatepay.payment-code-digits", 4),
                config.getInt("donatepay.journal-keep-days", 365),
                config.getInt("pass.grant-days", 30),
                tiers,
                parseWarnings(config.getLongList("pass.expiry-warnings-minutes")),
                config.getBoolean("pass.expiry-bossbar", true),
                config.getBoolean("pass.expiry-warning-sound", true),
                config.getInt("pass.kick-after-seconds", 0),
                config.getBoolean("pass.gui-menu", true),
                config.getString("integrations.pass-group", ""),
                config.getBoolean("integrations.pass-group-remove-expired", true),
                config.getInt("auth.session-minutes", 30),
                config.getInt("auth.min-password-length", 8),
                config.getInt("auth.max-password-length", 64),
                maxLoginAttempts,
                config.getInt("auth.reminder-seconds", 5),
                config.getInt("auth.captcha-after-attempts", 3),
                config.getBoolean("auth.dialogs", true),
                config.getBoolean("auth.button-login", true),
                config.getString("auth.button-login-symbols", "!@#$%&*-_+=.?"),
                config.getBoolean("auth.mask-commands-in-logs", true),
                config.getBoolean("auth.session-skips-2fa", false),
                config.getBoolean("auth.session-require-same-uuid", true),
                config.getBoolean("auth.reject-common-passwords", true),
                config.getBoolean("auth.reject-nickname-as-password", true),
                config.getBoolean("auth.password-strength-hint", true),
                parseBackoff(config.getIntegerList("auth.brute-force.backoff-seconds")),
                config.getInt("auth.brute-force.account-max-attempts", 8),
                config.getInt("auth.brute-force.account-window-minutes", 10),
                config.getInt("auth.brute-force.per-ip-max-attempts", 15),
                config.getInt("auth.brute-force.per-ip-window-minutes", 10),
                config.getInt("auth.brute-force.temp-ip-ban-minutes", 15),
                config.getBoolean("auth.brute-force.notify-admins", true),
                config.getBoolean("totp.enabled", true),
                config.getString("totp.issuer", "CHRDK"),
                config.getInt("totp.window-steps", 1),
                config.getInt("totp.max-attempts", maxLoginAttempts),
                config.getBoolean("totp.qr-map", true),
                config.getBoolean("anti-multialt.enabled", true),
                config.getString("anti-multialt.mode", "notify"),
                config.getInt("anti-multialt.max-accounts-per-ip", 3),
                config.getInt("anti-multialt.window-minutes", 240),
                config.getInt("storage.save-debounce-seconds", 5),
                config.getBoolean("storage.write-async", true),
                config.getInt("storage.backup-count", 14),
                config.getBoolean("storage.backup-on-start", true),
                config.getBoolean("audit.enabled", true),
                config.getInt("audit.keep-days", 180),
                config.getBoolean("audit.log-logins", true),
                config.getString("notifications.discord-webhook-url", ""),
                config.getStringList("notifications.events"),
                config.getStringList("notifications.mentions"),
                config.getBoolean("metrics.bstats", true),
                zone,
                ChatConfig.fromBukkitConfig(config),
                config.getInt("donatepay.health-seconds", 300),
                config.getInt("donatepay.alert-fail-streak", 10),
                config.getBoolean("donatepay.alert-admins", true),
                config.getInt("donatepay.alert-repeat-minutes", 15),
                config.getInt("donatepay.pending-notify-hours", 24),
                config.getBoolean("easydonate.enabled", false),
                config.getString("easydonate.shop-key", ""),
                config.getString("easydonate.payment-url", ""),
                config.getBoolean("donatepay.enabled", true)
        );
        parsed.boosty = BoostySettings.from(config);
        parsed.trademc = TradeMcSettings.from(config);
        parsed.motd = MotdSettings.from(config);
        parsed.passEnabled = config.getBoolean("pass.enabled", true);
        parsed.donateActions = DonateAction.parseList(config.getMapList("donation-actions"));
        parsed.donatePayShowBalance = config.getBoolean("donatepay.show-balance", true);
        parsed.serverMenu = ServerMenuSettings.from(config);
        return parsed;
    }

    /* ------------------------------------------------------------------ *
     *  DonatePay
     * ------------------------------------------------------------------ */

    public String donatePayApiToken() {
        return donatePayApiToken;
    }

    public String donatePayPaymentUrl() {
        return donatePayPaymentUrl;
    }

    public int minimumDonationRubles() {
        return minimumDonationRubles;
    }

    public int donatePollSeconds() {
        return donatePollSeconds;
    }

    /** Interval of the DonatePay connectivity heartbeat (console line, seconds). */
    public int donateHealthSeconds() {
        return donateHealthSeconds;
    }

    /** Сколько подряд неудачных опросов считается падением DonatePay. */
    public int donateAlertFailStreak() {
        return donateAlertFailStreak;
    }

    /** Дублировать тревогу о падении DonatePay онлайн-админам в чат. */
    public boolean donateAlertAdmins() {
        return donateAlertAdmins;
    }

    /** Повторная тревога, если отказ DonatePay тянется (минуты; 0 = без повторов). */
    public int donateAlertRepeatMinutes() {
        return donateAlertRepeatMinutes;
    }

    /** Напоминать админам, если донат ждёт разбора дольше N часов (0 = выкл). */
    public int donatePendingNotifyHours() {
        return donatePendingNotifyHours;
    }

    /** EasyDonate-провайдер включён и настроен (shop-key задан). */
    public boolean easyDonateReady() {
        return easyDonateEnabled && !easyDonateShopKey.isBlank();
    }

    public String easyDonateShopKey() {
        return easyDonateShopKey;
    }

    /** Флаг включения без привязки к ключу (для doctor: «включён, но ключа нет»). */
    public boolean easyDonateEnabled() {
        return easyDonateEnabled;
    }

    /** Подписочный Boosty-провайдер. НИКОГДА не null. */
    /** Консольные действия по донатам ({@code donation-actions}). */
    public List<DonateAction> donateActions() {
        return donateActions;
    }

    /** Показывать ли в консоли имя кошелька и баланс при рукопожатии DonatePay. */
    public boolean donatePayShowBalance() {
        return donatePayShowBalance;
    }

    /** Настройки серверного меню {@code /menu}. */
    public ServerMenuSettings serverMenu() {
        return serverMenu;
    }

    public BoostySettings boosty() {
        return boosty;
    }

    /** Настройки магазина TradeMC (trademc:<key> секция). */
    public TradeMcSettings trademc() {
        return trademc;
    }

    /** Ссылка, которую видит игрок как «продлить/купить проходку»: в TradeMC-режиме — ссылка магазина. */
    public String effectiveDonateUrl() {
        return trademc.enabled() && !trademc.paymentUrl().isBlank()
                ? trademc.paymentUrl() : donatePayPaymentUrl;
    }

    public boolean hasEffectiveDonateUrl() {
        return effectiveDonateUrl() != null && !effectiveDonateUrl().isBlank();
    }

    /** Подпись провайдера ссылки у игроков: «TradeMC» в RCON-режиме, иначе «DonatePay». */
    public String effectiveDonateLabel() {
        return trademc.enabled() ? "TradeMC" : "DonatePay";
    }

    /** Динамический MOTD. НИКОГДА не null. */
    public MotdSettings motd() {
        return motd;
    }

    /**
     * Включена ли донат-стена проходки ({@code pass.enabled: true} по умолчанию).
     * При выключении регистрация/2FA остаются, но игроки без проходки больше
     * не получают подсказок-ссылок, actionbar и не кикаются — игровой контроль
     * входаcделают очередь (CHRDK_Queue) и ограничения сервера.
     */
    public boolean passEnabled() {
        return passEnabled;
    }

    /** Ссылка на страницу EasyDonate-магазина для игроков (пусто = не показывать). */
    public String easyDonatePaymentUrl() {
        return easyDonatePaymentUrl;
    }

    public boolean strictCaseMatch() {
        return strictCaseMatch;
    }

    public boolean donateReviewUnmatched() {
        return donateReviewUnmatched;
    }

    public boolean donateFuzzyMatch() {
        return donateFuzzyMatch;
    }

    public boolean paymentCodeEnabled() {
        return paymentCodeEnabled;
    }

    public int paymentCodeDigits() {
        return paymentCodeDigits;
    }

    public int donationJournalKeepDays() {
        return donationJournalKeepDays;
    }

    public boolean donatePayReady() {
        return donatePayEnabled && !donatePayApiToken.isBlank();
    }

    /** Флаг включения провайдера DonatePay (donatepay.enabled). */
    public boolean donatePayEnabled() {
        return donatePayEnabled;
    }

    public boolean hasPaymentUrl() {
        return !donatePayPaymentUrl.isBlank();
    }

    /* ------------------------------------------------------------------ *
     *  Pass
     * ------------------------------------------------------------------ */

    public int grantDays() {
        return grantDays;
    }

    public List<PassTier> passTiers() {
        return passTiers;
    }

    /**
     * Resolves how many days a donation of the given amount grants:
     * the highest tier whose {@code amountRubles} does not exceed the paid sum,
     * or {@code grantDays} when no tier matches.
     */
    public int daysForAmount(int rubles) {
        int days = 0;
        for (PassTier tier : passTiers) {
            if (rubles >= tier.amountRubles() && tier.days() > days) {
                days = tier.days();
            }
        }
        return days > 0 ? days : grantDays;
    }

    /** Returns the tier label that best matches the paid amount. */
    public String tierLabelForAmount(int rubles) {
        PassTier best = null;
        for (PassTier tier : passTiers) {
            if (rubles >= tier.amountRubles() && (best == null || tier.days() > best.days())) {
                best = tier;
            }
        }
        return best == null ? "" : best.label();
    }

    public List<Long> expiryWarningMinutes() {
        return expiryWarningMinutes;
    }

    public boolean expiryBossBar() {
        return expiryBossBar;
    }

    public boolean expiryWarningSound() {
        return expiryWarningSound;
    }

    public int kickAfterSeconds() {
        return kickAfterSeconds;
    }

    /** Включено ли инвентарное GUI-меню {@code /pass menu}. */
    public boolean passGuiMenu() {
        return passGuiMenu;
    }

    /** Группа LuckPerms, синхронизируемая с активной проходкой (пусто — выключено). */
    public String passGroup() {
        return passGroup;
    }

    /** Снимать LuckPerms-группу при истечении проходки. */
    public boolean passGroupRemoveExpired() {
        return passGroupRemoveExpired;
    }

    /* ------------------------------------------------------------------ *
     *  Auth
     * ------------------------------------------------------------------ */

    public int sessionMinutes() {
        return sessionMinutes;
    }

    public int minPasswordLength() {
        return minPasswordLength;
    }

    public int maxPasswordLength() {
        return maxPasswordLength;
    }

    public int maxLoginAttempts() {
        return maxLoginAttempts;
    }

    public int reminderSeconds() {
        return reminderSeconds;
    }

    /**
     * Порог неудачных попыток входа, после которого включается эскалационная
     * капча (0 — выключена).
     */
    public int captchaAfterAttempts() {
        return captchaAfterAttempts;
    }

    public boolean authDialogs() {
        return authDialogs;
    }

    /** Инвентарная клавиатура входа (клики вместо набора пароля в чат) для {@code /login} без аргументов. */
    public boolean buttonLogin() {
        return buttonLogin;
    }

    /** Дополнительные знаки на странице «цифры и знаки» кнопочной клавиатуры. */
    public String buttonLoginSymbols() {
        return buttonLoginSymbols;
    }

    public boolean maskCommandsInLogs() {
        return maskCommandsInLogs;
    }

    public boolean sessionSkips2fa() {
        return sessionSkips2fa;
    }

    public boolean sessionRequireSameUuid() {
        return sessionRequireSameUuid;
    }

    public boolean rejectCommonPasswords() {
        return rejectCommonPasswords;
    }

    public boolean rejectNicknameAsPassword() {
        return rejectNicknameAsPassword;
    }

    public boolean passwordStrengthHint() {
        return passwordStrengthHint;
    }

    public List<Integer> backoffSeconds() {
        return backoffSeconds;
    }

    /** Returns the delay (seconds) a player has to wait before the n-th attempt is processed. */
    public int backoffForAttempt(int attempt) {
        if (backoffSeconds.isEmpty()) return 0;
        int index = Math.clamp(attempt - 1, 0, backoffSeconds.size() - 1);
        return backoffSeconds.get(index);
    }

    public int accountMaxAttempts() {
        return accountMaxAttempts;
    }

    public int accountWindowMinutes() {
        return accountWindowMinutes;
    }

    public int perIpMaxAttempts() {
        return perIpMaxAttempts;
    }

    public int perIpWindowMinutes() {
        return perIpWindowMinutes;
    }

    public int tempIpBanMinutes() {
        return tempIpBanMinutes;
    }

    public boolean bruteForceNotifyAdmins() {
        return bruteForceNotifyAdmins;
    }

    /* ------------------------------------------------------------------ *
     *  2FA
     * ------------------------------------------------------------------ */

    public boolean totpEnabled() {
        return totpEnabled;
    }

    public String totpIssuer() {
        return totpIssuer;
    }

    public int totpWindowSteps() {
        return totpWindowSteps;
    }

    public int totpMaxAttempts() {
        return totpMaxAttempts;
    }

    /** Выдавать ли карту с QR-кодом otpauth:// при {@code /2fa setup} и по {@code /2fa qr}. */
    public boolean totpQrMap() {
        return totpQrMap;
    }

    /* ------------------------------------------------------------------ *
     *  Anti multi-account, storage, audit, notifications, metrics
     * ------------------------------------------------------------------ */

    public boolean antiMultiAltEnabled() {
        return antiMultiAltEnabled;
    }

    public String antiMultiAltMode() {
        return antiMultiAltMode;
    }

    public boolean antiMultiAltDeny() {
        return antiMultiAltEnabled && "deny".equals(antiMultiAltMode);
    }

    public int maxAccountsPerIp() {
        return maxAccountsPerIp;
    }

    public int antiMultiAltWindowMinutes() {
        return antiMultiAltWindowMinutes;
    }

    public int storageSaveDebounceSeconds() {
        return storageSaveDebounceSeconds;
    }

    public boolean storageWriteAsync() {
        return storageWriteAsync;
    }

    public int storageBackupCount() {
        return storageBackupCount;
    }

    public boolean storageBackupOnStart() {
        return storageBackupOnStart;
    }

    public boolean auditEnabled() {
        return auditEnabled;
    }

    public int auditKeepDays() {
        return auditKeepDays;
    }

    public boolean auditLogLogins() {
        return auditLogLogins;
    }

    public String discordWebhookUrl() {
        return discordWebhookUrl;
    }

    public boolean discordEnabled() {
        return !discordWebhookUrl.isBlank();
    }

    public List<String> notificationEvents() {
        return notificationEvents;
    }

    public List<String> notificationMentions() {
        return notificationMentions;
    }

    public boolean notifyOn(String event) {
        return notificationEvents.contains(event);
    }

    public boolean bStatsEnabled() {
        return bStatsEnabled;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public ChatConfig chat() {
        return chat;
    }

    /* ------------------------------------------------------------------ *
     *  Helpers
     * ------------------------------------------------------------------ */

    public static List<PassTier> parseTiers(List<Map<?, ?>> raw) {
        List<PassTier> tiers = new ArrayList<>();
        if (raw == null) return tiers;
        for (Map<?, ?> entry : raw) {
            if (entry == null) continue;
            Object amount = entry.get("amount");
            Object days = entry.get("days");
            Object label = entry.get("label");
            int amountRubles = asInt(amount, 0);
            int tierDays = asInt(days, 0);
            if (amountRubles <= 0 || tierDays <= 0) continue;
            tiers.add(new PassTier(amountRubles, Math.clamp(tierDays, 1, 3650),
                    label == null ? "" : label.toString()));
        }
        tiers.sort(Comparator.comparingInt(PassTier::amountRubles));
        return tiers;
    }

    private static List<PassTier> normalizeTiers(List<PassTier> provided, int fallbackDays) {
        if (provided == null || provided.isEmpty()) {
            return defaultTiers(fallbackDays);
        }
        List<PassTier> tiers = new ArrayList<>(provided);
        tiers.sort(Comparator.comparingInt(PassTier::amountRubles));
        return List.copyOf(tiers);
    }

    private static List<PassTier> defaultTiers(int days) {
        return List.of(new PassTier(150, days, "1 месяц"));
    }

    private static List<Long> parseWarnings(List<Long> raw) {
        return sanitizeWarnings(raw);
    }

    private static List<Long> sanitizeWarnings(List<Long> raw) {
        if (raw == null || raw.isEmpty()) return List.of(10_080L, 4_320L, 1_440L, 60L);
        List<Long> result = new ArrayList<>();
        for (Long value : raw) {
            if (value != null && value > 0) result.add(value);
        }
        if (result.isEmpty()) return List.of(60L);
        result.sort(Comparator.reverseOrder());
        return List.copyOf(result);
    }

    private static List<Integer> parseBackoff(List<Integer> raw) {
        return raw == null ? List.of() : List.copyOf(raw);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static ZoneId parseZone(String raw) {
        try {
            return raw == null || raw.isBlank() ? ZoneId.of("Europe/Moscow") : ZoneId.of(raw.trim());
        } catch (RuntimeException ignored) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    private static String sanitizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return "";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return trimmed;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ *
     *  Boosty (подписка → автопродление проходки)
     * ------------------------------------------------------------------ */

    /** Настройки подписочного провайдера Boosty.to (boosty:<key> секция). */
    public static final class BoostySettings {
        public static final BoostySettings DISABLED = new BoostySettings(
                false, "", "", "", "", "", 300, 30, 24, false, false);

        private final boolean enabled;
        private final String blog;
        private final String blogUrl;
        private final String token;
        private final String refreshToken;
        private final String deviceId;
        private final int minLevelPrice;
        private final int daysPerPeriod;
        private final int recheckHours;
        private final boolean dryRun;
        private final boolean debugLog;

        BoostySettings(boolean enabled, String blog, String blogUrl, String token, String refreshToken,
                       String deviceId, int minLevelPrice, int daysPerPeriod, int recheckHours,
                       boolean dryRun, boolean debugLog) {
            this.enabled = enabled;
            this.blog = blog;
            this.blogUrl = blogUrl;
            this.token = token;
            this.refreshToken = refreshToken;
            this.deviceId = deviceId;
            this.minLevelPrice = minLevelPrice;
            this.daysPerPeriod = daysPerPeriod;
            this.recheckHours = recheckHours;
            this.dryRun = dryRun;
            this.debugLog = debugLog;
        }

        static BoostySettings from(FileConfiguration config) {
            return new BoostySettings(
                    config.getBoolean("boosty.enabled", false),
                    config.getString("boosty.blog", "").trim(),
                    config.getString("boosty.blog-url", "").trim(),
                    config.getString("boosty.token", ""),
                    config.getString("boosty.refresh-token", ""),
                    config.getString("boosty.device-id", ""),
                    config.getInt("boosty.min-level-price", 300),
                    Math.clamp(config.getInt("boosty.days-per-period", 30), 1, 365),
                    Math.clamp(config.getInt("boosty.recheck-hours", 24), 1, 168),
                    config.getBoolean("boosty.dry-run", false),
                    config.getBoolean("boosty.debug-log", false));
        }

        public boolean enabled() { return enabled; }
        public String blog() { return blog; }
        public String blogUrl() { return blogUrl; }
        public String token() { return token; }
        public String refreshToken() { return refreshToken; }
        public String deviceId() { return deviceId; }
        public int minLevelPrice() { return minLevelPrice; }
        public int daysPerPeriod() { return daysPerPeriod; }
        public int recheckHours() { return recheckHours; }
        public boolean dryRun() { return dryRun; }
        public boolean debugLog() { return debugLog; }

        /** Есть хотя бы одна пара авторизации (access ИЛИ refresh+device). */
        public boolean hasAuth() {
            return !token.isBlank() || (!refreshToken.isBlank() && !deviceId.isBlank());
        }

        /** Всё ли есть, чтобы клиент реально полетел в сеть. */
        public boolean ready() {
            return enabled && !blog.isBlank() && hasAuth();
        }

        /** Ссылка для игроков: blog-url, иначе собираем из имени блога. */
        public String linkForPlayers() {
            if (!blogUrl.isBlank()) {
                return blogUrl;
            }
            return blog.isBlank() ? "" : "https://boosty.to/" + blog;
        }
    }


    /* ------------------------------------------------------------------ *
     *  TradeMC — магазин с доставкой по RCON (trademc:<key> секция)
     * ------------------------------------------------------------------ */

    /** Настройки магазина TradeMC. Режим enabled выключает опросы DonatePay/EasyDonate и Boosty. */
    public static final class TradeMcSettings {
        public static final TradeMcSettings DISABLED = new TradeMcSettings(false, 0, "");

        private final boolean enabled;
        private final int shopId;
        private final String paymentUrl;

        TradeMcSettings(boolean enabled, int shopId, String paymentUrl) {
            this.enabled = enabled;
            this.shopId = shopId;
            this.paymentUrl = paymentUrl;
        }

        static TradeMcSettings from(FileConfiguration config) {
            return new TradeMcSettings(
                    config.getBoolean("trademc.enabled", false),
                    Math.max(0, config.getInt("trademc.shop-id", 0)),
                    config.getString("trademc.payment-url", "").trim());
        }

        public boolean enabled() { return enabled; }
        public int shopId() { return shopId; }
        public String paymentUrl() { return paymentUrl; }
    }


    /* ------------------------------------------------------------------ *
     *  Динамический MOTD (список серверов)
     * ------------------------------------------------------------------ */

    /** Настройки динамического MOTD (motd:<key> секция). */
    public static final class MotdSettings {
        public static final MotdSettings DISABLED = new MotdSettings(false, 60, List.of(), "rotate");

        private final boolean enabled;
        private final int rotateSeconds;
        private final List<String> lines;
        /** Режим выбора строки: {@code rotate} — по временному ковшу, {@code random} — случайно на пинг. */
        private final String mode;

        MotdSettings(boolean enabled, int rotateSeconds, List<String> lines, String mode) {
            this.enabled = enabled;
            this.rotateSeconds = rotateSeconds;
            this.lines = List.copyOf(lines);
            this.mode = "random".equalsIgnoreCase(mode) ? "random" : "rotate";
        }

        static MotdSettings from(FileConfiguration config) {
            return new MotdSettings(
                    config.getBoolean("motd.enabled", false),
                    Math.clamp(config.getInt("motd.rotate-seconds", 60), 1, 3600),
                    config.getStringList("motd.lines"),
                    config.getString("motd.mode", "rotate").trim());
        }

        public boolean enabled() { return enabled; }
        public int rotateSeconds() { return rotateSeconds; }
        public List<String> lines() { return lines; }
        public String mode() { return mode; }
    }
}
