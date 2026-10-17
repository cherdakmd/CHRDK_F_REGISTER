package ru.cherdakmd.chrdkbp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Прогресс сезона: battlepass-data.json.
 * Схема: { uuid: { "progress": {"id": N}, "claimed": ["id:FREE", "id:VIP"] } }.
 */
final class BattlePassStore {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE =
            new TypeToken<Map<String, Map<String, Object>>>() {
            }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> claimed = new HashMap<>();
    private volatile boolean dirty;

    BattlePassStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("battlepass-data.json");
        this.logger = logger;
    }

    synchronized void load() {
        progress.clear();
        claimed.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Map<String, Object>> parsed = GSON.fromJson(reader, MAP_TYPE);
            if (parsed == null) {
                return;
            }
            for (Map.Entry<String, Map<String, Object>> entry : parsed.entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                if (entry.getValue().get("progress") instanceof Map<?, ?> rows) {
                    Map<String, Integer> perMission = new HashMap<>();
                    for (Map.Entry<?, ?> row : rows.entrySet()) {
                        if (row.getValue() instanceof Number number) {
                            perMission.put(String.valueOf(row.getKey()), number.intValue());
                        }
                    }
                    progress.put(uuid, perMission);
                }
                if (entry.getValue().get("claimed") instanceof Iterable<?> list) {
                    Set<String> marks = new HashSet<>();
                    for (Object mark : list) {
                        marks.add(String.valueOf(mark));
                    }
                    claimed.put(uuid, marks);
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_BattlePass] Не удалось прочитать battlepass-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Integer>> entry : progress.entrySet()) {
            Map<String, Object> row = new HashMap<>();
            row.put("progress", entry.getValue());
            Set<String> marks = claimed.get(entry.getKey());
            if (marks != null && !marks.isEmpty()) {
                row.put("claimed", marks.stream().sorted().toList());
            }
            out.put(entry.getKey().toString(), row);
        }
        // игроки, которые забирали награды, но не набили прогресса (редкий кейс)
        for (Map.Entry<UUID, Set<String>> entry : claimed.entrySet()) {
            out.computeIfAbsent(entry.getKey().toString(), key -> {
                Map<String, Object> row = new HashMap<String, Object>();
                row.put("progress", Map.of());
                return row;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) out.get(entry.getKey().toString());
            row.put("claimed", entry.getValue().stream().sorted().toList());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_BattlePass] Не удалось записать battlepass-data.json: "
                    + exception.getMessage());
        }
    }

    /** Прогресс по заданию растёт только до цели — лишние тиксы не храним. */
    synchronized int addProgress(UUID uuid, String missionId, int delta, int target) {
        Map<String, Integer> perMission = progress.computeIfAbsent(uuid, key -> new HashMap<>());
        int next = Math.min(target, perMission.getOrDefault(missionId, 0) + Math.max(0, delta));
        perMission.put(missionId, next);
        dirty = true;
        return next;
    }

    synchronized int progressOf(UUID uuid, String missionId) {
        return progress.getOrDefault(uuid, Map.of()).getOrDefault(missionId, 0);
    }

    synchronized boolean isClaimed(UUID uuid, String missionId, String track) {
        return claimed.getOrDefault(uuid, Set.of()).contains(missionId + ":" + track);
    }

    synchronized boolean markClaimed(UUID uuid, String missionId, String track) {
        boolean added = claimed.computeIfAbsent(uuid, key -> new HashSet<>())
                .add(missionId + ":" + track);
        if (added) {
            dirty = true;
        }
        return added;
    }

    synchronized boolean known(UUID uuid) {
        return progress.containsKey(uuid) || claimed.containsKey(uuid);
    }

    synchronized void reset(UUID uuid) {
        progress.remove(uuid);
        claimed.remove(uuid);
        dirty = true;
    }

    boolean isDirty() {
        return dirty;
    }
}
