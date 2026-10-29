package ru.cherdakmd.chrdkcosmetics;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Legacy UUID→trail читается; v2 добавляет независимое предпочтение эффекта входа. */
final class SelectionStore {
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private final Path file;
    private final Logger logger;
    private final Map<UUID, String> selection = new HashMap<>();
    private final Map<UUID, Boolean> join = new HashMap<>();
    private final Object writer = new Object();
    private boolean dirty;
    SelectionStore(Path folder, Logger logger) { file = folder.resolve("cosmetics-data.json"); this.logger = logger; }
    synchronized void load() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> root = GSON.fromJson(reader, TYPE);
            if (root == null) return;
            Map<UUID, String> trails = new HashMap<>(); Map<UUID, Boolean> preferences = new HashMap<>();
            Object raw = root.containsKey("version") ? root.get("trails") : root;
            if (!(raw instanceof Map<?, ?> rows)) throw new IllegalArgumentException("invalid trails map");
            for (var entry : rows.entrySet()) {
                UUID id = uuid(entry.getKey());
                if (id != null && entry.getValue() instanceof String value && value.matches("[a-z0-9_.:-]{1,64}")) trails.put(id, value);
            }
            if (root.get("join") instanceof Map<?, ?> flags) for (var entry : flags.entrySet()) {
                UUID id = uuid(entry.getKey());
                if (id != null && Boolean.FALSE.equals(entry.getValue())) preferences.put(id, false);
            }
            selection.clear(); selection.putAll(trails); join.clear(); join.putAll(preferences); dirty = false;
        } catch (Exception failure) {
            if (logger != null) logger.log(Level.WARNING, "[CHRDK_Cosmetics] Не удалось прочитать cosmetics-data.json: " + failure.getMessage());
        }
    }
    private static UUID uuid(Object value) {
        try { return UUID.fromString(String.valueOf(value)); } catch (IllegalArgumentException ignored) { return null; }
    }
    void save() {
        // Снимок после захвата writer: более старый async-save не перезапишет финальный shutdown-save.
        synchronized (writer) {
            String json;
            synchronized (this) {
                if (!dirty) return;
                json = GSON.toJson(Map.of("version", 2, "trails", Map.copyOf(selection), "join", Map.copyOf(join)));
                dirty = false;
            }
            if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Cosmetics]")) synchronized (this) { dirty = true; }
        }
    }
    synchronized String selectionOf(UUID id) { return selection.get(id); }
    synchronized void select(UUID id, String trail) {
        if (id == null) return;
        if (trail != null && !trail.matches("[a-z0-9_.:-]{1,64}")) throw new IllegalArgumentException("invalid trail id");
        String old = trail == null ? selection.remove(id) : selection.put(id, trail);
        if (!java.util.Objects.equals(old, trail)) dirty = true;
    }
    synchronized boolean joinEnabled(UUID id) { return !Boolean.FALSE.equals(join.get(id)); }
    synchronized void joinEnabled(UUID id, boolean value) {
        if (id == null || joinEnabled(id) == value) return;
        if (value) join.remove(id); else join.put(id, false);
        dirty = true;
    }
    synchronized boolean isDirty() { return dirty; }
}
