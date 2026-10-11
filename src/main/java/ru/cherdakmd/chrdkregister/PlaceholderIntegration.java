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
 *     <li>{@code %chrdk_registered%}, {@code %chrdk_2fa%}, {@code %chrdk_muted%} — «да»/«нет».</li>
 * </ul>
 */
public final class PlaceholderIntegration extends PlaceholderExpansion {

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
        if (offlinePlayer == null || params == null || params.isBlank()) return "";

        long now = System.currentTimeMillis();
        String name = offlinePlayer.getName();
        RegistrationStore.PlayerAccount account = name == null
                ? null
                : store.getAccount(name).orElse(null);
        long passUntil = account == null ? 0 : account.passUntil;
        Player live = offlinePlayer.getPlayer();

        return switch (params.toLowerCase(Locale.ROOT)) {
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
}
