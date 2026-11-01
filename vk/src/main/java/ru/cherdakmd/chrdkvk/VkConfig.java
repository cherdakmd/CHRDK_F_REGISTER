package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.util.Locale;

/**
 * Неизменяемый снимок настроек {@code CHRDK_VK}.
 *
 * <p>Все числовые параметры зажимаются в безопасных границах, а публичный URL
 * сообщества проверяется так, чтобы его можно было безопасно вставлять в теги
 * MiniMessage без риска сломать разметку.</p>
 */
public record VkConfig(
        boolean enabled,
        long groupId,
        String communityUrl,
        String wikiUrl,
        String accessToken,
        String apiVersion,
        String apiBaseUrl,
        boolean keyboardsEnabled,
        boolean inlineSecurityButtons,
        boolean longPollEnabled,
        int longPollWaitSeconds,
        int longPollReconnectSeconds,
        int codeLength,
        int codeTtlSeconds,
        int codeCooldownSeconds,
        int maxAccountsPerVk,
        int linkBoostHours,
        int memberBoostHours,
        int recheckMinutes,
        int donutShopBonusPercent,
        int donutGraceHours,
        boolean labelsEnabled,
        boolean labelsRequireMembership,
        String memberTag,
        String donutTag,
        boolean loginAlerts,
        boolean bruteforceAlerts,
        boolean expiryAlerts,
        long adminPeerId,
        boolean groupChatEnabled,
        long groupChatPeerId,
        boolean groupChatMcToVk,
        boolean groupChatVkToMc,
        boolean groupChatRequireLinked,
        boolean groupChatAllowInfoCommands,
        boolean groupChatResolveMentions,
        int groupChatBatchWindowMs,
        boolean groupChatRelayJoinQuit,
        int groupChatVkCooldownSeconds,
        int groupChatMaxLength,
        String groupChatMcBadge,
        String groupChatVkFormat,
        boolean announceWallPosts,
        int wallPollMinutes,
        boolean wallIgnorePinned,
        boolean wallIgnoreAds,
        boolean wallIgnoreReposts,
        int wallPreviewLength,
        boolean allowRemoteLogout,
        boolean allowRemoteFreeze,
        boolean onboardingEnabled,
        int onboardingIntervalSeconds,
        long onboardingPeerId,
        String onboardingInviteUrl
) {

    public static final String DEFAULT_COMMUNITY_URL = "https://vk.ru/chrdk_reborn";
    public static final String DEFAULT_WIKI_URL = "https://cherdakmd.github.io/CHRDK_F_REGISTER/";
    public static final String DEFAULT_API_BASE_URL = "https://api.vk.com/method/";
    public static final String DEFAULT_API_VERSION = "5.199";
    public static final long MIN_CONVERSATION_PEER_ID = 2_000_000_001L;

    public static VkConfig parse(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("enabled", true);
        long groupId = Math.max(0L, cfg.getLong("community.group-id", 0L));
        String communityUrl = sanitizeHttpsUrl(cfg.getString("community.url", DEFAULT_COMMUNITY_URL),
                DEFAULT_COMMUNITY_URL);
        String wikiUrl = sanitizeHttpsUrl(cfg.getString("community.wiki-url", DEFAULT_WIKI_URL),
                DEFAULT_WIKI_URL);
        String accessToken = trim(cfg.getString("community.access-token", ""));
        String apiVersion = trimOrDefault(cfg.getString("community.api-version", DEFAULT_API_VERSION),
                DEFAULT_API_VERSION);
        String apiBase = sanitizeHttpsUrl(cfg.getString("community.api-base-url", DEFAULT_API_BASE_URL),
                DEFAULT_API_BASE_URL);
        if (!apiBase.endsWith("/")) {
            apiBase = apiBase + "/";
        }

        boolean keyboardsEnabled = cfg.getBoolean("keyboard.enabled", true);
        boolean inlineSecurityButtons = cfg.getBoolean("keyboard.inline-security-buttons", true);

        boolean longPollEnabled = cfg.getBoolean("long-poll.enabled", true);
        int waitSeconds = Math.clamp(cfg.getInt("long-poll.wait-seconds", 25), 5, 25);
        int reconnectSeconds = Math.clamp(cfg.getInt("long-poll.reconnect-seconds", 5), 2, 60);

        int codeLength = Math.clamp(cfg.getInt("linking.code-length", 6), 4, 10);
        int codeTtlSeconds = Math.clamp(cfg.getInt("linking.code-ttl-seconds", 300), 60, 1800);
        int cooldownSeconds = Math.clamp(cfg.getInt("linking.cooldown-seconds", 15), 0, 300);
        int maxAccountsPerVk = Math.clamp(cfg.getInt("linking.max-accounts-per-vk", 1), 1, 5);

        int linkBoostHours = Math.clamp(cfg.getInt("rewards.link-boost-hours", 3), 0, 24);
        int memberBoostHours = Math.clamp(cfg.getInt("rewards.member-boost-hours", 3), 0, 24);
        int recheckMinutes = Math.clamp(cfg.getInt("rewards.recheck-minutes", 30), 5, 720);
        int donutShopBonusPercent = Math.clamp(cfg.getInt("rewards.donut-shop-bonus-percent", 10), 0, 50);
        int donutGraceHours = Math.clamp(cfg.getInt("rewards.donut-grace-hours", 72), 1, 720);

        boolean labelsEnabled = cfg.getBoolean("labels.enabled", true);
        boolean labelsRequireMembership = cfg.getBoolean("labels.require-membership", false);
        String memberTag = trimOrDefault(cfg.getString("labels.member-tag", "<aqua>[ВК]</aqua>"),
                "<aqua>[ВК]</aqua>");
        String donutTag = trimOrDefault(cfg.getString("labels.donut-tag", "<gold>[VK Donut]</gold>"),
                "<gold>[VK Donut]</gold>");

        boolean loginAlerts = cfg.getBoolean("notifications.login-alerts", true);
        boolean bruteforceAlerts = cfg.getBoolean("notifications.bruteforce-alerts", true);
        boolean expiryAlerts = cfg.getBoolean("notifications.expiry-alerts", true);
        long adminPeerId = Math.max(0L, cfg.getLong("notifications.admin-peer-id", 0L));

        boolean groupChatEnabled = cfg.getBoolean("group-chat.enabled", true);
        long rawChatPeer = cfg.getLong("group-chat.peer-id", 0L);
        long groupChatPeerId = rawChatPeer >= MIN_CONVERSATION_PEER_ID ? rawChatPeer : 0L;
        boolean groupChatMcToVk = cfg.getBoolean("group-chat.minecraft-to-vk", true);
        boolean groupChatVkToMc = cfg.getBoolean("group-chat.vk-to-minecraft", true);
        boolean groupChatRequireLinked = cfg.getBoolean("group-chat.require-linked", true);
        boolean groupChatAllowInfoCommands = cfg.getBoolean("group-chat.allow-info-commands", true);
        boolean groupChatResolveMentions = cfg.getBoolean("group-chat.resolve-linked-mentions", true);
        int groupChatBatchWindowMs = Math.clamp(cfg.getInt("group-chat.batch-window-ms", 500), 0, 3_000);
        boolean groupChatRelayJoinQuit = cfg.getBoolean("group-chat.relay-join-quit", false);
        int groupChatVkCooldownSeconds = Math.clamp(
                cfg.getInt("group-chat.vk-sender-cooldown-seconds", 3), 0, 60);
        int groupChatMaxLength = Math.clamp(
                cfg.getInt("group-chat.max-message-length", 220), 40, 400);
        String groupChatMcBadge = trimOrDefault(
                cfg.getString("group-chat.mc-badge", "<#0077FF>[ВК-чат]</#0077FF>"),
                "<#0077FF>[ВК-чат]</#0077FF>");
        String groupChatVkFormat = trimOrDefault(
                cfg.getString("group-chat.vk-format", "[MC] {player}: {message}"),
                "[MC] {player}: {message}");

        boolean announceWallPosts = cfg.getBoolean("wall.announce-new-posts", true);
        // Community token не поддерживает wall.get: оставляем только события Long Poll.
        int wallPollMinutes = 0;
        boolean wallIgnorePinned = cfg.getBoolean("wall.ignore-pinned", true);
        boolean wallIgnoreAds = cfg.getBoolean("wall.ignore-ads", true);
        boolean wallIgnoreReposts = cfg.getBoolean("wall.ignore-reposts", true);
        int wallPreviewLength = Math.clamp(cfg.getInt("wall.preview-length", 140), 40, 300);

        boolean allowRemoteLogout = cfg.getBoolean("security.allow-remote-logout", true);
        boolean allowRemoteFreeze = cfg.getBoolean("security.allow-remote-freeze", true);

        return new VkConfig(
                enabled, groupId, communityUrl, wikiUrl, accessToken, apiVersion, apiBase,
                keyboardsEnabled, inlineSecurityButtons,
                longPollEnabled, waitSeconds, reconnectSeconds,
                codeLength, codeTtlSeconds, cooldownSeconds, maxAccountsPerVk,
                linkBoostHours, memberBoostHours, recheckMinutes, donutShopBonusPercent, donutGraceHours,
                labelsEnabled, labelsRequireMembership, memberTag, donutTag,
                loginAlerts, bruteforceAlerts, expiryAlerts, adminPeerId,
                groupChatEnabled, groupChatPeerId, groupChatMcToVk, groupChatVkToMc,
                groupChatRequireLinked, groupChatAllowInfoCommands, groupChatResolveMentions,
                groupChatBatchWindowMs, groupChatRelayJoinQuit, groupChatVkCooldownSeconds,
                groupChatMaxLength, groupChatMcBadge, groupChatVkFormat,
                announceWallPosts, wallPollMinutes, wallIgnorePinned, wallIgnoreAds, wallIgnoreReposts,
                wallPreviewLength, allowRemoteLogout, allowRemoteFreeze,
                cfg.getBoolean("onboarding.enabled", true),
                Math.clamp(cfg.getInt("onboarding.interval-seconds", 180), 60, 3600),
                cfg.getLong("onboarding.peer-id", 0) >= MIN_CONVERSATION_PEER_ID ? cfg.getLong("onboarding.peer-id") : 0,
                sanitizeHttpsUrl(cfg.getString("onboarding.chat-invite-url", ""), "")
        );
    }

    /** Готов ли клиент к обращениям в VK API (заданы и group-id, и токен сообщества). */
    public boolean apiConfigured() {
        return enabled && groupId > 0L && !accessToken.isEmpty();
    }

    /** Прямая ссылка на запись стены сообщества ВКонтакте. */
    public String wallPostUrl(long postId) {
        if (groupId <= 0L || postId <= 0L) {
            return communityUrl;
        }
        String host = communityUrl != null && communityUrl.toLowerCase(Locale.ROOT).contains("vk.ru")
                ? "vk.ru" : "vk.com";
        return "https://" + host + "/wall-" + groupId + "_" + postId;
    }

    static String sanitizeHttpsUrl(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || trimmed.indexOf('\'') >= 0
                || trimmed.indexOf('"') >= 0
                || trimmed.indexOf('<') >= 0
                || trimmed.indexOf('>') >= 0
                || trimmed.indexOf('\\') >= 0
                || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return fallback;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")
                    || host == null || host.isBlank()) {
                return fallback;
            }
            return trimmed;
        } catch (IllegalArgumentException badUri) {
            return fallback;
        }
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String trimOrDefault(String raw, String fallback) {
        String trimmed = trim(raw);
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
