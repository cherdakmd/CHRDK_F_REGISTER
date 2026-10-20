package ru.cherdakmd.chrdkstreams;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

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
 *   <li>{@code %chrdkstreams_max_today%} — дневной потолок.</li>
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
}
