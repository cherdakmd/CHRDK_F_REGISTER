package ru.cherdakmd.chrdkstreams;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Плейсхолдеры PlaceholderAPI для таб-листа и скорбордов.
 *
 * <p>Только сохранённые данные — никаких сетевых запросов: плейсхолдер может
 * запрашиваться десятки раз в секунду, и опрос Twitch там недопустим.</p>
 *
 * <ul>
 *   <li>{@code %chrdkstreams_channel%} — канал или {@code —};</li>
 *   <li>{@code %chrdkstreams_platform%} — Twitch / VK Play Live;</li>
 *   <li>{@code %chrdkstreams_verified%} — «да» / «нет»;</li>
 *   <li>{@code %chrdkstreams_pending%} — минут накоплено до следующих суток;</li>
 *   <li>{@code %chrdkstreams_today%} — суток получено сегодня;</li>
 *   <li>{@code %chrdkstreams_days%} — суток получено всего;</li>
 *   <li>{@code %chrdkstreams_rule%} — минут эфира за сутки (из конфига);</li>
 *   <li>{@code %chrdkstreams_max_today%} — дневной потолок;</li>
 *   <li>{@code %chrdkstreams_watching%} — чей эфир смотрит игрок (или {@code —});</li>
 *   <li>{@code %chrdkstreams_watch_pending%} — минут просмотра до следующих суток;</li>
 *   <li>{@code %chrdkstreams_watch_today%} — суток за просмотр сегодня;</li>
 *   <li>{@code %chrdkstreams_watch_days%} — суток за просмотр всего;</li>
 *   <li>{@code %chrdkstreams_watchers%} — подтверждённых зрителей у стримера.</li>
 * </ul>
 */
public final class StreamPlaceholders extends PlaceholderExpansion {

    private static final String NONE = "—";

    private final StreamPlugin plugin;

    StreamPlaceholders(StreamPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdkstreams";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        StreamConfig config = plugin.config();
        if ("rule".equals(key)) {
            return Integer.toString(config.minutesPerDay());
        }
        if ("max_today".equals(key)) {
            return Integer.toString(config.maxDaysPerDay());
        }
        if (player == null) {
            return "";
        }
        if (isViewerKey(key)) {
            return viewerValue(player.getUniqueId(), key);
        }
        Optional<StreamBinding> found = plugin.store().get(player.getUniqueId());
        if (found.isEmpty()) {
            return switch (key) {
                case "channel", "platform" -> NONE;
                case "verified" -> "нет";
                case "pending", "today", "days" -> "0";
                default -> null;
            };
        }
        StreamBinding binding = found.get();
        return switch (key) {
            case "channel" -> binding.channel();
            case "platform" -> binding.platform().displayName();
            case "verified" -> binding.verified() ? "да" : "нет";
            case "pending" -> Long.toString(
                    StreamRewards.pendingMinutes(binding.state(), config.rule()));
            case "today" -> Integer.toString(binding.state().awardedToday());
            case "days" -> Integer.toString(binding.state().totalDays());
            default -> null;
        };
    }

    private static boolean isViewerKey(String key) {
        return "watching".equals(key) || "watchers".equals(key) || key.startsWith("watch_");
    }

    /** Зрительские плейсхолдеры: только сохранённые данные, без запросов к площадкам. */
    private String viewerValue(UUID uuid, String key) {
        if ("watchers".equals(key)) {
            return Integer.toString(plugin.viewerStore().viewersOf(uuid).size());
        }
        Optional<ViewerBinding> found = plugin.viewerStore().viewer(uuid);
        if (found.isEmpty()) {
            return switch (key) {
                case "watching" -> NONE;
                case "watch_pending", "watch_today", "watch_days" -> "0";
                default -> null;
            };
        }
        ViewerBinding binding = found.get();
        StreamRewards.State state = plugin.viewerStore().progress(uuid);
        StreamRewards.Rule rule = plugin.config().viewers().rule();
        return switch (key) {
            case "watching" -> binding.streamerName().isBlank() ? NONE : binding.streamerName();
            case "watch_pending" -> Long.toString(StreamRewards.pendingMinutes(state, rule));
            case "watch_today" -> Integer.toString(state.awardedToday());
            case "watch_days" -> Integer.toString(state.totalDays());
            default -> null;
        };
    }
}
