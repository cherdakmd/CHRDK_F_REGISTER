package ru.cherdakmd.chrdkskills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Маленький durable-журнал суточного лимита зелий; при повреждении расходники закрываются. */
final class SkillXpStore {
    private static final int MAX_PLAYERS = 200_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Logger logger;
    private final Object writeLock = new Object();
    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private boolean available = true;
    private boolean dirty;

    private static final class Row {
        String day = "";
        int used;
        long cooldownUntil;
    }

    SkillXpStore(Path folder, Logger logger) {
        file = folder.resolve("skill-consumables.json");
        this.logger = logger;
    }

    synchronized boolean available() { return available; }
    synchronized int used(UUID id) {
        Row row = rows.get(id);
        return row == null || !today().equals(row.day) ? 0 : row.used;
    }
    synchronized long cooldownLeft(UUID id, long now) {
        Row row = rows.get(id);
        return row == null ? 0 : Math.max(0L, (row.cooldownUntil - now + 999L) / 1000L);
    }

    /** Одна атомарная проверка+трата; отклонённое зелье остаётся в инвентаре. */
    synchronized boolean consume(UUID id, int limit, long now, long cooldownMillis) {
        if (!available || id == null || limit < 1 || rows.size() >= MAX_PLAYERS && !rows.containsKey(id)) return false;
        Row row = rows.computeIfAbsent(id, ignored -> new Row());
        String day = today();
        if (!day.equals(row.day)) { row.day = day; row.used = 0; }
        if (row.used >= limit || row.cooldownUntil > now) return false;
        row.used++;
        row.cooldownUntil = now + cooldownMillis;
        dirty = true;
        return true;
    }

    synchronized void load() {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1 || !root.get("players").isJsonObject()) throw new IllegalArgumentException("schema");
            Map<UUID, Row> loaded = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject("players").entrySet()) {
                if (loaded.size() >= MAX_PLAYERS) throw new IllegalArgumentException("too many players");
                JsonObject json = entry.getValue().getAsJsonObject();
                Row row = new Row();
                row.day = json.get("day").getAsString();
                row.used = json.get("used").getAsInt();
                row.cooldownUntil = json.get("cooldownUntil").getAsLong();
                if (row.used < 0 || row.used > SkillXpPolicy.MAX_DAILY_USES || row.cooldownUntil < 0) throw new IllegalArgumentException("values");
                loaded.put(UUID.fromString(entry.getKey()), row);
            }
            rows.clear(); rows.putAll(loaded);
        } catch (Exception error) {
            available = false;
            if (logger != null) logger.severe("Зелья навыков заблокированы: восстановите skill-consumables.json из backup. " + error.getMessage());
        }
    }

    void saveNow() {
        synchronized (writeLock) {
            String json;
            synchronized (this) {
                if (!available || !dirty) return;
                JsonObject root = new JsonObject(), players = new JsonObject();
                root.addProperty("version", 1);
                for (var entry : rows.entrySet()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("day", entry.getValue().day);
                    value.addProperty("used", entry.getValue().used);
                    value.addProperty("cooldownUntil", entry.getValue().cooldownUntil);
                    players.add(entry.getKey().toString(), value);
                }
                root.add("players", players);
                json = GSON.toJson(root);
                dirty = false;
            }
            if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Skills consumables]")) synchronized (this) { dirty = true; }
        }
    }

    private static String today() { return LocalDate.now(ZoneOffset.UTC).toString(); }
}
