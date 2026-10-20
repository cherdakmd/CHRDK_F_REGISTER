package ru.cherdakmd.chrdkstreams;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Хранилище привязок и прогресса: {@code streams-data.json}.
 *
 * <p>Формат — массив игроков с явными ключами. Чтение терпимое: битая запись
 * пропускается, а не уносит с собой остальные. Запись атомарная (temp + move):
 * падение сервера посреди сохранения не оставит половину файла.</p>
 */
final class StreamStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, StreamBinding> bindings = new LinkedHashMap<>();

    StreamStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("streams-data.json");
        this.logger = logger;
    }

    synchronized void load() {
        bindings.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("players")) {
                return;
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("players")) {
                StreamBinding binding = read(element);
                if (binding != null) {
                    bindings.put(binding.uuid(), binding);
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.warning("[Streams] Не удалось прочитать streams-data.json: "
                    + exception.getMessage());
        }
    }

    private static StreamBinding read(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            UUID uuid = UUID.fromString(string(entry, "uuid"));
            Optional<StreamPlatform> platform = StreamPlatform.parse(string(entry, "platform"));
            String channel = string(entry, "channel");
            if (platform.isEmpty() || channel.isEmpty()) {
                return null;
            }
            String code = string(entry, "code");
            long verifiedAt = longOf(entry, "verifiedAt");
            StreamRewards.State state = new StreamRewards.State(
                    (int) longOf(entry, "day"),
                    (int) longOf(entry, "awardedToday"),
                    longOf(entry, "pendingMillis"),
                    (int) longOf(entry, "totalDays"));
            return new StreamBinding(uuid, string(entry, "name"), platform.get(),
                    channel, code, verifiedAt, state);
        } catch (RuntimeException exception) {
            return null; // битая строка — пропускаем, остальные важнее
        }
    }

    synchronized void save() {
        JsonArray players = new JsonArray();
        for (StreamBinding binding : bindings.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", binding.uuid().toString());
            entry.addProperty("name", binding.playerName());
            entry.addProperty("platform", binding.platform().id());
            entry.addProperty("channel", binding.channel());
            entry.addProperty("code", binding.code());
            entry.addProperty("verifiedAt", binding.verifiedAt());
            entry.addProperty("day", binding.state().day());
            entry.addProperty("awardedToday", binding.state().awardedToday());
            entry.addProperty("pendingMillis", binding.state().pendingMillis());
            entry.addProperty("totalDays", binding.state().totalDays());
            players.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("players", players);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            logger.warning("[Streams] Не удалось сохранить streams-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized Optional<StreamBinding> get(UUID uuid) {
        return Optional.ofNullable(bindings.get(uuid));
    }

    /** Игрок с таким каналом на этой площадке (канал принадлежит только одному). */
    synchronized Optional<StreamBinding> byChannel(StreamPlatform platform, String channel) {
        String wanted = channel == null ? "" : channel.trim();
        for (StreamBinding binding : bindings.values()) {
            if (binding.platform() == platform && binding.channel().equalsIgnoreCase(wanted)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    synchronized void put(StreamBinding binding) {
        if (binding == null) {
            return;
        }
        bindings.put(binding.uuid(), binding);
    }

    synchronized boolean remove(UUID uuid) {
        return bindings.remove(uuid) != null;
    }

    synchronized Collection<StreamBinding> all() {
        return List.copyOf(new ArrayList<>(bindings.values()));
    }

    synchronized int size() {
        return bindings.size();
    }

    /** Суммарно начисленных суток по всем игрокам (для логов). */
    synchronized int totalDays() {
        int sum = 0;
        for (StreamBinding binding : bindings.values()) {
            sum += Math.max(0, binding.state().totalDays());
        }
        return sum;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString().trim() : "";
    }

    private static long longOf(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsLong() : 0L;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }
}
