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
 * Хранилище зрительской программы: {@code streams-viewers.json}.
 *
 * <p>Две независимые коллекции:</p>
 * <ul>
 *   <li>{@code streamers} — у кого включён просмотр и какой код сейчас показан;</li>
 *   <li>{@code viewers} — привязки зрителей: заявка, подтверждение и прогресс.</li>
 * </ul>
 *
 * <p>Формат — явные ключи, чтение терпимое (битая строка пропускается), запись
 * атомарная: temp + move.</p>
 */
final class ViewerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, ViewerSettings> streamers = new LinkedHashMap<>();
    private final Map<UUID, ViewerBinding> viewers = new LinkedHashMap<>();
    /** Прогресс зрителя: живёт отдельно от привязки, поэтому отписка его не сбрасывает. */
    private final Map<UUID, StreamRewards.State> progress = new LinkedHashMap<>();

    ViewerStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("streams-viewers.json");
        this.logger = logger;
    }

    /* ------------------------------ чтение/запись ------------------------------ */

    synchronized void load() {
        streamers.clear();
        viewers.clear();
        progress.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            if (object.has("streamers") && object.get("streamers").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("streamers")) {
                    ViewerSettings settings = readSettings(element);
                    if (settings != null) {
                        streamers.put(settings.streamer(), settings);
                    }
                }
            }
            if (object.has("viewers") && object.get("viewers").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("viewers")) {
                    ViewerBinding binding = readBinding(element);
                    if (binding != null) {
                        viewers.put(binding.uuid(), binding);
                    }
                }
            }
            if (object.has("progress") && object.get("progress").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("progress")) {
                    Map.Entry<UUID, StreamRewards.State> entry = readProgress(element);
                    if (entry != null) {
                        progress.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.warning("[Streams] Не удалось прочитать streams-viewers.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        JsonArray streamerArray = new JsonArray();
        for (ViewerSettings settings : streamers.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", settings.streamer().toString());
            entry.addProperty("name", settings.streamerName());
            entry.addProperty("enabled", settings.enabled());
            entry.addProperty("code", settings.code());
            entry.addProperty("codeUntil", settings.codeUntil());
            streamerArray.add(entry);
        }
        JsonArray viewerArray = new JsonArray();
        for (ViewerBinding binding : viewers.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", binding.uuid().toString());
            entry.addProperty("name", binding.playerName());
            entry.addProperty("streamer", binding.streamer().toString());
            entry.addProperty("streamerName", binding.streamerName());
            entry.addProperty("accepted", binding.accepted());
            entry.addProperty("requestedAt", binding.requestedAt());
            entry.addProperty("lastCheckIn", binding.lastCheckIn());
            viewerArray.add(entry);
        }
        JsonArray progressArray = new JsonArray();
        for (Map.Entry<UUID, StreamRewards.State> state : progress.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", state.getKey().toString());
            entry.addProperty("day", state.getValue().day());
            entry.addProperty("awardedToday", state.getValue().awardedToday());
            entry.addProperty("pendingMillis", state.getValue().pendingMillis());
            entry.addProperty("totalHours", state.getValue().totalHours());
            progressArray.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("streamers", streamerArray);
        root.add("viewers", viewerArray);
        root.add("progress", progressArray);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            logger.warning("[Streams] Не удалось сохранить streams-viewers.json: "
                    + exception.getMessage());
        }
    }

    private static ViewerSettings readSettings(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            return new ViewerSettings(
                    UUID.fromString(string(entry, "uuid")),
                    string(entry, "name"),
                    bool(entry, "enabled"),
                    string(entry, "code"),
                    longOf(entry, "codeUntil"));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static ViewerBinding readBinding(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            UUID streamer = UUID.fromString(string(entry, "streamer"));
            UUID viewer = UUID.fromString(string(entry, "uuid"));
            return new ViewerBinding(viewer, string(entry, "name"), streamer,
                    string(entry, "streamerName"), bool(entry, "accepted"),
                    longOf(entry, "requestedAt"), longOf(entry, "lastCheckIn"));
        } catch (RuntimeException exception) {
            return null; // битая строка — пропускаем
        }
    }

    /* ------------------------------ стримеры ------------------------------ */

    synchronized Optional<ViewerSettings> settings(UUID streamer) {
        return Optional.ofNullable(streamers.get(streamer));
    }

    synchronized void putSettings(ViewerSettings settings) {
        if (settings != null) {
            streamers.put(settings.streamer(), settings);
        }
    }

    /** Выключить просмотр у стримера. Привязки зрителей остаются: их прогресс не сгорает. */
    synchronized boolean removeSettings(UUID streamer) {
        return streamers.remove(streamer) != null;
    }

    synchronized Collection<ViewerSettings> allStreamers() {
        return List.copyOf(new ArrayList<>(streamers.values()));
    }

    synchronized int streamerCount() {
        int count = 0;
        for (ViewerSettings settings : streamers.values()) {
            if (settings.enabled()) {
                count++;
            }
        }
        return count;
    }

    /* ------------------------------ зрители ------------------------------ */

    synchronized Optional<ViewerBinding> viewer(UUID viewer) {
        return Optional.ofNullable(viewers.get(viewer));
    }

    synchronized void putViewer(ViewerBinding binding) {
        if (binding != null) {
            viewers.put(binding.uuid(), binding);
        }
    }

    synchronized boolean removeViewer(UUID viewer) {
        return viewers.remove(viewer) != null;
    }

    synchronized Collection<ViewerBinding> allViewers() {
        return List.copyOf(new ArrayList<>(viewers.values()));
    }

    synchronized List<ViewerBinding> viewersOf(UUID streamer) {
        List<ViewerBinding> result = new ArrayList<>();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer) && binding.accepted()) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    synchronized List<ViewerBinding> pendingOf(UUID streamer) {
        List<ViewerBinding> result = new ArrayList<>();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer) && !binding.accepted()) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    /** Привязки всех стримеров, которые сейчас подключены к просмотру. */
    synchronized List<ViewerBinding> viewersOfEnabledStreamers() {
        List<ViewerBinding> result = new ArrayList<>();
        for (ViewerBinding binding : viewers.values()) {
            ViewerSettings settings = streamers.get(binding.streamer());
            if (settings != null && settings.enabled()) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    /** Заявка от конкретного игрока к конкретному стримеру (для идемпотентности). */
    synchronized Optional<ViewerBinding> pendingByName(UUID streamer, String playerName) {
        String wanted = playerName == null ? "" : playerName.trim();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer) && !binding.accepted()
                    && binding.playerName().equalsIgnoreCase(wanted)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    synchronized int viewerCount() {
        return viewers.size();
    }

    /* ------------------------------ прогресс ------------------------------ */

    synchronized StreamRewards.State progress(UUID viewer) {
        StreamRewards.State state = progress.get(viewer);
        return state == null ? StreamRewards.State.empty() : state;
    }

    synchronized void putProgress(UUID viewer, StreamRewards.State state) {
        if (viewer == null || state == null || state.equals(StreamRewards.State.empty())) {
            progress.remove(viewer);
            return;
        }
        progress.put(viewer, state);
    }

    private static Map.Entry<UUID, StreamRewards.State> readProgress(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            UUID uuid = UUID.fromString(string(entry, "uuid"));
            StreamRewards.State state = new StreamRewards.State(
                    (int) longOf(entry, "day"),
                    (int) longOf(entry, "awardedToday"),
                    longOf(entry, "pendingMillis"),
                    // 1.4.184: ключ totalHours; старый totalDays читаем как часы.
                    (int) longOf(entry, "totalHours", "totalDays"));
            return Map.entry(uuid, state);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /* ------------------------------ разбор JSON ------------------------------ */

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString().trim() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    && object.get(key).getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Значение по новому ключу, а если его нет — по старому (совместимость данных). */
    private static long longOf(JsonObject object, String key, String legacy) {
        return object.has(key) ? longOf(object, key) : longOf(object, legacy);
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
