package ru.cherdakmd.chrdkstreams;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Площадка стримов, на которой игрок ведёт эфир.
 *
 * <p>Правила имени канала одинаковые у Twitch и VK Play Live: латиница, цифры и
 * подчёркивание, от 3 до 25 символов. Имя проверяется до запроса к API — так
 * игрок получает понятную ошибку, а плагин не дёргает чужой сервис мусором.</p>
 */
public enum StreamPlatform {

    TWITCH("twitch", "Twitch", "https://www.twitch.tv/%s"),
    VKPLAY("vkplay", "VK Play Live", "https://vkplay.live/%s");

    /** Логин Twitch: до 25 символов; VK Play — ник канала, те же символы. */
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_]{3,25}");

    private final String id;
    private final String displayName;
    private final String profileUrl;

    StreamPlatform(String id, String displayName, String profileUrl) {
        this.id = id;
        this.displayName = displayName;
        this.profileUrl = profileUrl;
    }

    /** Короткий id для конфига и data.json: {@code twitch}, {@code vkplay}. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Ссылка на канал — её игрок видит при привязке. */
    public String urlFor(String channel) {
        return String.format(profileUrl, channel);
    }

    public static Optional<StreamPlatform> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (StreamPlatform platform : values()) {
            if (platform.id.equals(key) || platform.name().equalsIgnoreCase(key)) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    /** Имя канала корректно для любой из площадок. */
    public static boolean validChannel(String channel) {
        return channel != null && CHANNEL.matcher(channel.trim()).matches();
    }

    /**
     * Имя канала в виде, в котором его ждёт API: без пробелов по краям.
     * Регистр сохраняем — VK Play Live показывает ники как есть.
     */
    public static String normalizeChannel(String channel) {
        return channel == null ? "" : channel.trim();
    }

    /** Служебные разделы сайтов, которые нельзя принять за имя канала. */
    private static final Set<String> RESERVED = Set.of(
            "directory", "videos", "video", "settings", "subscriptions", "inventory",
            "wallet", "friends", "search", "downloads", "turbo", "jobs", "store",
            "login", "signup", "embed", "clip", "clips", "category", "categories");

    /**
     * Имя канала из того, что ввёл игрок.
     *
     * <p>Стримеры привыкли копировать ссылку, а не ник, поэтому принимаем и её:
     * {@code https://www.twitch.tv/ник}, {@code twitch.tv/ник}, {@code vkplay.live/ник},
     * {@code @ник}. Из адреса берётся первый осмысленный сегмент пути: хвосты
     * вида {@code /videos} и параметры {@code ?ysclid=…} отбрасываются. Если
     * человек всё же вставил служебный раздел ({@code twitch.tv/directory}) —
     * возвращается пустая строка, и проверка канала покажет понятную ошибку.</p>
     */
    public static String extractChannel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        if (value.startsWith("@")) {
            return normalizeChannel(value.substring(1));
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return normalizeChannel(value);
        }
        String rest = value.substring(slash + 1);
        for (String segment : rest.split("/")) {
            String candidate = normalizeChannel(segment);
            if (candidate.isEmpty()) {
                continue;
            }
            String key = candidate.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(key)) {
                return "";
            }
            return candidate;
        }
        return "";
    }

    /** Список id площадок для сообщения «доступны: …». */
    public static String ids() {
        StringBuilder builder = new StringBuilder();
        for (StreamPlatform platform : values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(platform.id);
        }
        return builder.toString();
    }

    /** Площадки, у которых в конфиге есть ключи авторизации (для подсказок). */
    public static String ids(List<StreamPlatform> platforms) {
        StringBuilder builder = new StringBuilder();
        for (StreamPlatform platform : platforms) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(platform.id);
        }
        return builder.toString();
    }
}
