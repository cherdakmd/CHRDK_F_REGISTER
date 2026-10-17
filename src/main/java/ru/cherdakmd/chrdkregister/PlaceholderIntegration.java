package ru.cherdakmd.chrdkregister;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Интеграция с PlaceholderAPI (мягкая зависимость): плейсхолдеры вида {@code %chrdk_...%}
 * для таб-листа, скорбордов и голограмм:
 * <ul>
 *     <li>{@code %chrdk_pass_status%} — «активна» / «истекла» / «нет проходки»;</li>
 *     <li>{@code %chrdk_pass_left%} — остаток строкой («2 дн. 3 ч.»);</li>
 *     <li>{@code %chrdk_pass_days_left%} / {@code %chrdk_pass_hours_left%} — остаток в днях/часах;</li>
 *     <li>{@code %chrdk_pass_until%} — дата окончания проходки;</li>
 *     <li>{@code %chrdk_auth_stage%} / {@code %chrdk_auth_stage_id%} — стадия входа игрока;</li>
 *     <li>{@code %chrdk_chat_channel%} / {@code %chrdk_chat_channel_id%} — текущий канал чата;</li>
 *     <li>{@code %chrdk_registered%}, {@code %chrdk_2fa%}, {@code %chrdk_muted%} — «да»/«нет»;</li>
 *     <li>глобальные (без привязки к игроку): {@code %chrdk_online_total%}, {@code %chrdk_online_authed%},
 *     {@code %chrdk_accounts_total%}, {@code %chrdk_accounts_with_pass%}, {@code %chrdk_stats_revenue_24h%},
 *     {@code %chrdk_stats_revenue_7d%}, {@code %chrdk_stats_revenue_30d%}, {@code %chrdk_stats_pending%},
 *     {@code %chrdk_stats_granted%}, {@code %chrdk_stats_conversion%}, {@code %chrdk_pass_price%},
 *     {@code %chrdk_payment_url%}.</li>
 * </ul>
 */
public final class PlaceholderIntegration extends PlaceholderExpansion {

    private static final long DAY_MILLIS = 86_400_000L;

    private final AccessManager accessManager;
    private final ChatService chatService;
    private final RegistrationStore store;
    private final String version;

    public PlaceholderIntegration(
            String pluginVersion,
            AccessManager accessManager,
            ChatService chatService,
            RegistrationStore store
    ) {
        this.version = pluginVersion;
        this.accessManager = accessManager;
        this.chatService = chatService;
        this.store = store;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdk";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    /** Переживает {@code /papi reload}: плейсхолдеры не пропадают до перезапуска сервера. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @Nullable String params) {
        if (params == null || params.isBlank()) return "";

        long now = System.currentTimeMillis();
        String key = params.toLowerCase(Locale.ROOT);

        // Глобальные плейсхолдеры (серверная статистика, настройки оплаты) — игрок не нужен
        String global = resolveGlobal(key, now);
        if (global != null) return global;
        if (offlinePlayer == null) return "";

        String name = offlinePlayer.getName();
        RegistrationStore.PlayerAccount account = name == null
                ? null
                : store.getAccount(name).orElse(null);
        long passUntil = account == null ? 0 : account.passUntil;
        Player live = offlinePlayer.getPlayer();

        return switch (key) {
            case "pass_status" -> PlaceholderValues.passStatus(account, now);
            case "pass_left" -> PlaceholderValues.passLeft(account, now);
            case "pass_days_left" -> Long.toString(PlaceholderValues.wholeDaysLeft(passUntil, now));
            case "pass_hours_left" -> Long.toString(PlaceholderValues.wholeHoursLeft(passUntil, now));
            case "pass_until" -> passUntil > 0 ? accessManager.formatTime(passUntil) : PlaceholderValues.NONE;
            case "auth_stage" -> live == null
                    ? "оффлайн"
                    : PlaceholderValues.describeStage(accessManager.getStage(live));
            case "auth_stage_id" -> live == null
                    ? "offline"
                    : PlaceholderValues.stageId(accessManager.getStage(live));
            case "chat_channel" -> live == null
                    ? PlaceholderValues.NONE
                    : PlaceholderValues.describeChannel(chatService.currentChannel(live));
            case "chat_channel_id" -> live == null
                    ? PlaceholderValues.NONE
                    : PlaceholderValues.channelId(chatService.currentChannel(live));
            case "registered" -> PlaceholderValues.yesNo(account != null && account.isRegistered());
            case "2fa" -> PlaceholderValues.yesNo(account != null && account.isTotpEnabled());
            case "muted" -> PlaceholderValues.yesNo(PlaceholderValues.isMuted(account, now));
            default -> null; // неизвестный плейсхолдер — PlaceholderAPI оставит его как есть
        };
    }

    /**
     * Серверные плейсхолдеры без привязки к игроку: онлайн, счётчики аккаунтов,
     * статистика донатов (как {@code /pass stats}), цена и ссылка на оплату.
     *
     * @return значение плейсхолдера или {@code null}, если ключ не относится к глобальным
     */
    private @Nullable String resolveGlobal(String key, long now) {
        return switch (key) {
            case "online_total" -> Integer.toString(accessManager.countOnlinePlayers());
            case "online_authed" -> Integer.toString(accessManager.countAuthenticatedOnline());
            case "accounts_total" -> Integer.toString(store.getTotalAccountCount());
            case "accounts_with_pass" -> Integer.toString(store.getActivePassCount(now));
            case "stats_revenue_24h" -> DonationService.formatRubles(store.revenueKopecks(now - DAY_MILLIS, now));
            case "stats_revenue_7d" -> DonationService.formatRubles(store.revenueKopecks(now - 7 * DAY_MILLIS, now));
            case "stats_revenue_30d" -> DonationService.formatRubles(store.revenueKopecks(now - 30 * DAY_MILLIS, now));
            case "stats_pending" -> Integer.toString(accessManager.donations().pendingCount());
            case "stats_granted" -> Integer.toString(
                    store.countDonations(RegistrationStore.DonationStatus.GRANTED));
            case "stats_conversion" -> Integer.toString(PlaceholderValues.conversionPercent(
                    store.getActivePassCount(now), store.getTotalAccountCount()));
            case "pass_price" -> Integer.toString(accessManager.config().minimumDonationRubles());
            case "payment_url" -> accessManager.config().effectiveDonateUrl();
            default -> null;
        };
    }
}
