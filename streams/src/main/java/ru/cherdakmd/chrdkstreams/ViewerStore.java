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
 * <p>Три независимых раздела:</p>
 * <ul>
 *   <li>{@code streamers} — у кого включён просмотр, какой код сейчас показан;</li>
 *   <li>{@code viewers} — кто смотрит чей канал и подтверждён ли он;</li>
 *   <li>{@code progress} — накопленные минуты и выданные сутки <b>на зрителя</b>,
 *       а не на канал: смена канала или отписка не сбрасывает дневной потолок.</li>
 * </ul>
 *
 * <p>Запись атомарная (temp + move), битые строки пропускаются.</p>
 */
final class ViewerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, StreamerState> streamers = new LinkedHashMap<>();
    private final Map<UUID, ViewerBinding> viewers = new LinkedHashMap<>();
    private final Map<UUID, StreamRewards.State> progress = new LinkedHashMap<>();

    ViewerStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("streams-viewers.json");
        this.logger = logger;
    }

    /** Настройки просмотра у стримера. */
    record StreamerState(UUID streamer, String name, boolean enabled, String code, long codeUntil) {

        StreamerState {
            name = name == null ? "" : name.trim();
            code = code == null ? "" : code.trim();
        }

        StreamerState withCode(String newCode, long untilMillis) {
            return new StreamerState(streamer, name, enabled, newCode, untilMillis);
        }

        StreamerState renamed(String newName) {
            return new StreamerState(streamer, newName, enabled, code, codeUntil);
        }
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
            for (JsonElement element : array(object, "streamers")) {
                readStreamer(element).ifPresent(state -> streamers.put(state.streamer(), state));
            }
            for (JsonElement element : array(object, "viewers")) {
                readViewer(element).ifPresent(binding -> viewers.put(binding.uuid(), binding));
            }
            for (JsonElement element : array(object, "progress")) {
                readProgress(element).ifPresent(entry -> progress.put(entry.uuid(), entry.state()));
            }
        } catch (IOException | RuntimeException exception) {
            logger.warning("[Streams] Не удалось прочитать streams-viewers.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        JsonArray streamerArray = new JsonArray();
        for (StreamerState state : streamers.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", state.streamer().toString());
            entry.addProperty("name", state.name());
            entry.addProperty("enabled", state.enabled());
            entry.addProperty("code", state.code());
            entry.addProperty("codeUntil", state.codeUntil());
            streamerArray.add(entry);
        }
        JsonArray viewerArray = new JsonArray();
        for (ViewerBinding binding : viewers.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", binding.uuid().toString());
            entry.addProperty("name", binding.name());
            entry.addProperty("streamer", binding.streamer().toString());
            entry.addProperty("streamerName", binding.streamerName());
            entry.addProperty("accepted", binding.accepted());
            entry.addProperty("requestedAt", binding.requestedAt());
            entry.addProperty("lastCheckIn", binding.lastCheckIn());
            viewerArray.add(entry);
        }
        JsonArray progressArray = new JsonArray();
        for (Map.Entry<UUID, StreamRewards.State> entry : progress.entrySet()) {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", entry.getKey().toString());
            object.addProperty("day", entry.getValue().day());
            object.addProperty("awardedToday", entry.getValue().awardedToday());
            object.addProperty("pendingMillis", entry.getValue().pendingMillis());
            object.addProperty("totalDays", entry.getValue().totalDays());
            progressArray.add(object);
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

    private static JsonArray array(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonArray()
                ? root.getAsJsonArray(key) : new JsonArray();
    }

    private static Optional<StreamerState> readStreamer(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            return Optional.of(new StreamerState(
                    UUID.fromString(string(entry, "uuid")),
                    string(entry, "name"),
                    bool(entry, "enabled"),
                    string(entry, "code"),
                    longOf(entry, "codeUntil")));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ViewerBinding> readViewer(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            return Optional.of(new ViewerBinding(
                    UUID.fromString(string(entry, "uuid")),
                    string(entry, "name"),
                    UUID.fromString(string(entry, "streamer")),
                    string(entry, "streamerName"),
                    bool(entry, "accepted"),
                    longOf(entry, "requestedAt"),
                    longOf(entry, "lastCheckIn")));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ProgressEntry> readProgress(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject entry = element.getAsJsonObject();
        try {
            return Optional.of(new ProgressEntry(
                    UUID.fromString(string(entry, "uuid")),
                    new StreamRewards.State(
                            (int) longOf(entry, "day"),
                            (int) longOf(entry, "awardedToday"),
                            longOf(entry, "pendingMillis"),
                            (int) longOf(entry, "totalDays"))));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private record ProgressEntry(UUID uuid, StreamRewards.State state) {
    }

    /* ------------------------------ стримеры ------------------------------ */

    synchronized Optional<StreamerState> streamer(UUID streamer) {
        return Optional.ofNullable(streamers.get(streamer));
    }

    synchronized void putStreamer(StreamerState state) {
        if (state != null) {
            streamers.put(state.streamer(), state);
        }
    }

    synchronized Collection<StreamerState> allStreamers() {
        return List.copyOf(new ArrayList<>(streamers.values()));
    }

    /**
     * Выключает просмотр у стримера и убирает его зрителей.
     *
     * @return сколько заявок удалено
     */
    synchronized int removeStreamer(UUID streamer) {
        streamers.remove(streamer);
        int before = viewers.size();
        viewers.values().removeIf(binding -> binding.streamer().equals(streamer));
        return before - viewers.size();
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

    /** Убрать заявку зрителя (прогресс при этом сохраняется). */
    synchronized Optional<ViewerBinding> removeViewer(UUID viewer) {
        return Optional.ofNullable(viewers.remove(viewer));
    }

    synchronized Collection<ViewerBinding> viewersOf(UUID streamer) {
        List<ViewerBinding> result = new ArrayList<>();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer)) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    synchronized Collection<ViewerBinding> pendingOf(UUID streamer) {
        List<ViewerBinding> result = new ArrayList<>();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer) && !binding.accepted()) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    /** Зритель по нику среди заявок конкретного стримера. */
    synchronized Optional<ViewerBinding> pendingByName(UUID streamer, String playerName) {
        String wanted = playerName == null ? "" : playerName.trim();
        for (ViewerBinding binding : viewers.values()) {
            if (binding.streamer().equals(streamer) && !binding.accepted()
                    && binding.name().equalsIgnoreCase(wanted)) {
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
        return progress.getOrDefault(viewer, StreamRewards.State.empty());
    }

    synchronized void putProgress(UUID viewer, StreamRewards.State state) {
        if (viewer == null || state == null) {
            return;
        }
        if (state.equals(StreamRewards.State.empty())) {
            progress.remove(viewer);
        } else {
            progress.put(viewer, state);
        }
    }

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

    private static long longOf(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsLong() : 0L;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }
}
