package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Добровольные публичные ссылки: никогда не читает служебные привязки входа. */
final class PublicSocialLinks {
    enum Platform {
        VK("vk", "VK", "vk.com", "/[A-Za-z0-9_.-]{1,100}"),
        DISCORD("discord", "Discord", "discord.com", "/users/[0-9]{17,20}"),
        TELEGRAM("telegram", "Telegram", "t.me", "/[A-Za-z0-9_]{5,32}"),
        YOUTUBE("youtube", "YouTube", "youtube.com", "/(?:@[A-Za-z0-9_.-]{3,100}|(?:channel|c|user)/[A-Za-z0-9_.-]{1,100})"),
        TWITCH("twitch", "Twitch", "twitch.tv", "/[A-Za-z0-9_]{4,25}");

        final String id, label, host;
        final Pattern path;
        Platform(String id, String label, String host, String path) {
            this.id = id; this.label = label; this.host = host; this.path = Pattern.compile(path);
        }
        static Platform parse(String input) {
            if (input == null) return null;
            for (Platform platform : values()) if (platform.id.equalsIgnoreCase(input)) return platform;
            return null;
        }
    }

    private static final int MAX_FILE_BYTES = 8192;
    private final Path folder;
    private final Logger logger;

    PublicSocialLinks(Path dataFolder, Logger logger) {
        this.folder = dataFolder.resolve("players").resolve("public-social");
        this.logger = logger;
    }

    /** Строгий URL без редиректов, портов, userinfo, кодирования, query или fragment. */
    static Optional<String> validate(Platform platform, String input) {
        if (platform == null || input == null || input.length() > 200 || !input.equals(input.trim())) return Optional.empty();
        try {
            URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return Optional.empty();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (!host.equals(platform.host) || uri.getRawPath() == null
                    || !platform.path.matcher(uri.getRawPath()).matches()) return Optional.empty();
            return Optional.of("https://" + platform.host + uri.getRawPath());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /** Пустой Optional — файл недоступен или повреждён: нельзя затереть его новой записью. */
    synchronized Optional<Map<Platform, String>> read(UUID playerId) {
        if (playerId == null) return Optional.empty();
        Path file = folder.resolve(playerId + ".json");
        if (Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Optional.of(Map.of());
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("unsafe file");
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("version") || !root.get("version").isJsonPrimitive()
                    || !"1".equals(root.get("version").getAsString())
                    || !root.has("links") || !root.get("links").isJsonObject()) throw new IOException("unknown format");
            EnumMap<Platform, String> links = new EnumMap<>(Platform.class);
            for (var entry : root.getAsJsonObject("links").entrySet()) {
                Platform platform = Platform.parse(entry.getKey());
                if (platform == null || !entry.getKey().equals(platform.id) || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) throw new IOException("invalid platform");
                String value = validate(platform, entry.getValue().getAsString()).orElseThrow(() -> new IOException("invalid link"));
                links.put(platform, value);
            }
            return Optional.of(Map.copyOf(links));
        } catch (IOException | RuntimeException exception) {
            if (logger != null) logger.warning("[CHRDK_Core] Публичные соцсети " + playerId + " не прочитаны; файл не будет перезаписан: " + exception.getMessage());
            return Optional.empty();
        }
    }

    /** Сначала сохраняем на диск, и только при успехе сообщаем игроку об изменении. */
    synchronized boolean update(UUID playerId, Platform platform, String url) {
        if (playerId == null || platform == null) return false;
        Optional<Map<Platform, String>> old = read(playerId);
        if (old.isEmpty()) return false;
        EnumMap<Platform, String> next = new EnumMap<>(Platform.class);
        next.putAll(old.get());
        if (url == null) next.remove(platform);
        else {
            Optional<String> valid = validate(platform, url);
            if (valid.isEmpty()) return false;
            next.put(platform, valid.get());
        }
        JsonObject links = new JsonObject();
        for (Platform item : Platform.values()) if (next.containsKey(item)) links.addProperty(item.id, next.get(item));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("links", links);
        return SafeJsonWriter.write(folder.resolve(playerId + ".json"), root.toString(), logger, "[CHRDK_Core]");
    }
}
