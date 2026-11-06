package ru.cherdakmd.chrdksocial;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Неизменяемый снимок настроек единого социального плагина: обязательная
 * привязка к ВК/MAX, транспорт MAX Bot API и автопостинг новостей.
 *
 * <p>Все числовые параметры зажимаются в безопасных границах. Токен бота MAX
 * никогда не попадает в URL: он передаётся только заголовком {@code Authorization}.</p>
 */
public record SocialConfig(
        boolean enabled,
        boolean requireLink,
        int graceDays,
        String bypassPermission,
        int codeLength,
        int codeTtlSeconds,
        int codeCooldownSeconds,
        int maxAttempts,
        int maxAccountsPerProfile,
        int reminderSeconds,
        int rewardXpLevels,
        int emergencyMinutes,
        boolean failOpenOnCorruption,
        String vkCommunityUrl,
        String maxBotUrl,
        boolean maxEnabled,
        String maxApiBaseUrl,
        String maxAccessToken,
        String maxTransport,
        boolean webhookEnabled,
        int webhookPort,
        String webhookBind,
        String webhookPath,
        String webhookSecret,
        String webhookPublicUrl,
        String keystoreFile,
        String keystorePassword,
        boolean requireTls,
        boolean pollingEnabled,
        int pollingWaitSeconds,
        int pollingReconnectSeconds,
        int pollingLimit,
        List<String> updateTypes,
        long maxChatId,
        boolean bridgeEnabled,
        boolean bridgeMcToMax,
        boolean bridgeMaxToMc,
        boolean bridgeRequireLinked,
        String bridgeBadge,
        String bridgeFormat,
        int bridgeCooldownSeconds,
        int bridgeMaxLength,
        boolean newsEnabled,
        int newsPollMinutes,
        boolean newsInGame,
        String newsTemplate,
        String releaseApiUrl,
        int newcomerGraceDays,
        boolean donateEnabled,
        String donateMode,
        List<String> donateProductIds,
        boolean donatePermanent,
        String donateUrl,
        boolean reminderNagging,
        int reminderActionBarSeconds,
        int reminderActionNagSeconds,
        boolean reminderTitleOnJoin,
        boolean reminderSound
) {

    public static final String DEFAULT_MAX_API = "https://platform-api2.max.ru/";
    public static final String DEFAULT_VK_URL = "https://vk.ru/chrdk_reborn";
    public static final String DEFAULT_NEWS_TEMPLATE =
            "🚀 CHERDAK — выпуск %tag%: %title%\nПодробности и скачивание: %url%";
    public static final String DEFAULT_RELEASE_API =
            "https://api.github.com/repos/cherdakmd/CHRDK_F_REGISTER/releases/latest";
    public static final String DEFAULT_DONATE_URL = "https://chrdk.trademc.org/";
    public static final List<String> DEFAULT_UPDATE_TYPES = List.of(
            "message_created", "message_callback", "bot_started", "bot_added", "bot_removed");

    public static SocialConfig parse(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("social.enabled", true);
        boolean requireLink = cfg.getBoolean("social.require-link", true);
        int graceDays = Math.clamp(cfg.getInt("social.grace-days", 14), 0, 90);
        String bypass = trim(cfg.getString("social.bypass-permission", "chrdksocial.bypass"));
        int codeLength = Math.clamp(cfg.getInt("social.code-length", 6), 4, 10);
        int ttl = Math.clamp(cfg.getInt("social.code-ttl-seconds", 300), 60, 1800);
        int cooldown = Math.clamp(cfg.getInt("social.code-cooldown-seconds", 20), 0, 300);
        int attempts = Math.clamp(cfg.getInt("social.max-attempts", 5), 1, 20);
        int maxAccounts = Math.clamp(cfg.getInt("social.max-accounts-per-profile", 1), 1, 8);
        // Напоминания в чат не чаще раза в 3 минуты: периодический таймер и пинг
        // при действиях делят один общий кулдаун (SocialService.allowChatReminder).
        int reminder = Math.clamp(cfg.getInt("social.reminder-seconds", 180), 10, 600);
        int reward = Math.clamp(cfg.getInt("social.reward-xp-levels", 0), 0, 30);
        int emergency = Math.clamp(cfg.getInt("social.emergency-minutes", 30), 1, 720);
        boolean failOpen = cfg.getBoolean("social.fail-open-on-corruption", true);
        String vkUrl = sanitizeHttps(cfg.getString("social.vk-community-url", DEFAULT_VK_URL), DEFAULT_VK_URL);
        String maxUrl = trim(cfg.getString("social.max-bot-url", ""));

        boolean maxEnabled = cfg.getBoolean("max.enabled", true);
        String apiBase = sanitizeHttps(cfg.getString("max.api-base-url", DEFAULT_MAX_API), DEFAULT_MAX_API);
        if (!apiBase.endsWith("/")) apiBase = apiBase + "/";
        String token = trim(cfg.getString("max.access-token", ""));
        String transport = trim(cfg.getString("max.transport", "webhook")).toLowerCase(Locale.ROOT);
        if (!transport.equals("webhook") && !transport.equals("polling")) transport = "webhook";

        boolean webhookEnabled = cfg.getBoolean("max.webhook.enabled", true);
        int port = Math.clamp(cfg.getInt("max.webhook.port", 8443), 1, 65535);
        String bind = trim(cfg.getString("max.webhook.bind-address", "0.0.0.0"));
        if (bind.isEmpty()) bind = "0.0.0.0";
        String path = trim(cfg.getString("max.webhook.path", "/max/webhook"));
        if (!path.startsWith("/")) path = "/" + path;
        String secret = trim(cfg.getString("max.webhook.secret", ""));
        String publicUrl = sanitizeHttps(cfg.getString("max.webhook.public-url", ""), "");
        String keystore = trim(cfg.getString("max.webhook.keystore", "max-webhook.p12"));
        String keystorePassword = cfg.getString("max.webhook.keystore-password", "");
        boolean requireTls = cfg.getBoolean("max.webhook.require-tls", true);

        boolean pollingEnabled = cfg.getBoolean("max.polling.enabled", true);
        int waitSeconds = Math.clamp(cfg.getInt("max.polling.wait-seconds", 25), 1, 60);
        int reconnect = Math.clamp(cfg.getInt("max.polling.reconnect-seconds", 5), 2, 60);
        int limit = Math.clamp(cfg.getInt("max.polling.limit", 100), 1, 1000);
        List<String> updateTypes = parseUpdateTypes(cfg.getStringList("max.update-types"));
        long maxChatId = Math.max(0L, cfg.getLong("max.chat-id", 0L));

        boolean bridgeEnabled = cfg.getBoolean("max.group-chat.enabled", true);
        boolean mcToMax = cfg.getBoolean("max.group-chat.minecraft-to-max", true);
        boolean maxToMc = cfg.getBoolean("max.group-chat.max-to-minecraft", true);
        boolean requireLinked = cfg.getBoolean("max.group-chat.require-linked", true);
        String badge = trimOrDefault(cfg.getString("max.group-chat.mc-badge", "<#00C3FF>[MAX]</#00C3FF>"),
                "<#00C3FF>[MAX]</#00C3FF>");
        String format = trimOrDefault(cfg.getString("max.group-chat.max-format", "[MC] {player}: {message}"),
                "[MC] {player}: {message}");
        int bridgeCooldown = Math.clamp(cfg.getInt("max.group-chat.sender-cooldown-seconds", 3), 0, 60);
        int bridgeMaxLength = Math.clamp(cfg.getInt("max.group-chat.max-message-length", 220), 40, 400);

        boolean newsEnabled = cfg.getBoolean("news.enabled", true);
        int newsPoll = Math.clamp(cfg.getInt("news.poll-minutes", 30), 5, 720);
        boolean inGame = cfg.getBoolean("news.announce-in-game", true);
        String template = trimOrDefault(cfg.getString("news.template", DEFAULT_NEWS_TEMPLATE), DEFAULT_NEWS_TEMPLATE);
        String releaseApi = sanitizeHttps(cfg.getString("news.release-api-url", DEFAULT_RELEASE_API), DEFAULT_RELEASE_API);

        int newcomerGraceDays = Math.clamp(cfg.getInt("social.newcomer-grace-days", 14), 0, 90);
        boolean donateEnabled = cfg.getBoolean("social.donate-access.enabled", true);
        String donateMode = SocialAccessPolicy.normalizeMode(
                cfg.getString("social.donate-access.mode", SocialAccessPolicy.MODE_ANY_RANK));
        List<String> productIds = parseProductIds(cfg.getStringList("social.donate-access.product-ids"));
        boolean donatePermanent = cfg.getBoolean("social.donate-access.permanent", false);
        String donateUrl = sanitizeHttps(
                cfg.getString("social.donate-access.url", DEFAULT_DONATE_URL), DEFAULT_DONATE_URL);

        // Назойливое информирование: работает и В grace, а не только после заморозки.
        boolean nagging = cfg.getBoolean("social.reminder.nagging", true);
        int actionBarSeconds = Math.clamp(cfg.getInt("social.reminder.action-bar-seconds", 15), 2, 60);
        int actionNagSeconds = Math.clamp(cfg.getInt("social.reminder.action-nag-seconds", 120), 5, 300);
        boolean titleOnJoin = cfg.getBoolean("social.reminder.title-on-join", true);
        boolean reminderSound = cfg.getBoolean("social.reminder.sound", true);

        return new SocialConfig(enabled, requireLink, graceDays, bypass, codeLength, ttl, cooldown,
                attempts, maxAccounts, reminder, reward, emergency, failOpen, vkUrl, maxUrl,
                maxEnabled, apiBase, token, transport, webhookEnabled, port, bind, path, secret,
                publicUrl, keystore, keystorePassword == null ? "" : keystorePassword, requireTls,
                pollingEnabled, waitSeconds, reconnect, limit, updateTypes, maxChatId,
                bridgeEnabled, mcToMax, maxToMc, requireLinked, badge, format,
                bridgeCooldown, bridgeMaxLength, newsEnabled, newsPoll, inGame, template, releaseApi,
                newcomerGraceDays, donateEnabled, donateMode, productIds, donatePermanent, donateUrl,
                nagging, actionBarSeconds, actionNagSeconds, titleOnJoin, reminderSound);
    }

    /** Достаточно ли данных для сетевых запросов MAX Bot API. */
    public boolean maxApiConfigured() {
        return maxEnabled && !maxAccessToken.isEmpty();
    }

    /** Можно ли поднять защищённый webhook: есть порт, путь, секрет и публичный HTTPS-адрес. */
    /** MAX принимает secret только из A-Z a-z 0-9 _ и дефиса, длиной 5..256 символов. */
    public boolean webhookSecretValid() {
        return webhookSecret.matches("[A-Za-z0-9_-]{5,256}");
    }

    public boolean webhookConfigured() {
        return maxEnabled && webhookEnabled && webhookPort > 0 && !webhookPath.isBlank()
                && webhookSecretValid() && !webhookPublicUrl.isBlank();
    }

    public boolean mandatoryActive() {
        return enabled && requireLink;
    }

    public long graceMillis() {
        return graceDays * SocialAccessPolicy.DAY_MILLIS;
    }

    public long newcomerGraceMillis() {
        return newcomerGraceDays * SocialAccessPolicy.DAY_MILLIS;
    }

    /** Платный доступ активен только при явном режиме и включённом флаге. */
    public boolean donateActive() {
        return donateEnabled && SocialAccessPolicy.modeActive(donateMode);
    }

    private static List<String> parseProductIds(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                String id = trim(value);
                if (id.length() > 64 || id.isEmpty() || out.contains(id)) continue;
                if (!id.matches("[A-Za-z0-9._-]+")) continue;
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> parseUpdateTypes(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                String trimmed = trim(value).toLowerCase(Locale.ROOT);
                if (DEFAULT_UPDATE_TYPES.contains(trimmed) && !out.contains(trimmed)) out.add(trimmed);
            }
        }
        return out.isEmpty() ? DEFAULT_UPDATE_TYPES : List.copyOf(out);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimOrDefault(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** Только HTTPS, без пробелов и управляющих символов: адрес вставляется в MiniMessage и в API. */
    private static String sanitizeHttps(String value, String fallback) {
        String trimmed = trim(value);
        if (!trimmed.startsWith("https://") || trimmed.length() > 300) return fallback;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < 0x20) return fallback;
        }
        return trimmed;
    }
}
