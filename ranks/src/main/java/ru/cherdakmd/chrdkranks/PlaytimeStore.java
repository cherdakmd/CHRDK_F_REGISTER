package ru.cherdakmd.chrdkranks;

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
 * Хранилище наигранного времени: ranks-data.json рядом с плагином.
 * Схема: { uuid: { "seconds": N, "awarded": ["id1", ...] } }.
 */
final class PlaytimeStore {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Map<String, Object>>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Long> seconds = new HashMap<>();
    private final Map<UUID, Set<String>> awarded = new HashMap<>();
    private volatile boolean dirty;

    PlaytimeStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("ranks-data.json");
        this.logger = logger;
    }

    synchronized void load() {
        seconds.clear();
        awarded.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Map<String, Object>> parsed = GSON.fromJson(reader, MAP_TYPE);
            if (parsed == null) {
                return;
            }
            for (Map.Entry<String, Map<String, Object>> entry : parsed.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    Object sec = entry.getValue().get("seconds");
                    if (sec instanceof Number number) {
                        seconds.put(uuid, number.longValue());
                    }
                    if (entry.getValue().get("awarded") instanceof Iterable<?> list) {
                        Set<String> ids = new HashSet<>();
                        for (Object id : list) {
                            ids.add(String.valueOf(id));
                        }
                        awarded.put(uuid, ids);
                    }
                } catch (IllegalArgumentException ignored) {
                    // мусорный ключ — пропускаем
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Ranks] Не удалось прочитать ranks-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        Map<String, Object> out = new HashMap<>();
        // объединяем ключи: бывают игроки только с awarded-метками (без секунд)
        Set<UUID> all = new HashSet<>(seconds.keySet());
        all.addAll(awarded.keySet());
        for (UUID uuid : all) {
            Map<String, Object> row = new HashMap<>();
            long total = seconds.getOrDefault(uuid, 0L);
            if (total > 0) {
                row.put("seconds", total);
            }
            Set<String> ids = awarded.get(uuid);
            if (ids != null && !ids.isEmpty()) {
                row.put("awarded", ids.stream().sorted().toList());
            }
            if (!row.isEmpty()) {
                out.put(uuid.toString(), row);
            }
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
            logger.log(Level.WARNING, "[CHRDK_Ranks] Не удалось записать ranks-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized long addSeconds(UUID uuid, long deltaSeconds) {
        long total = seconds.getOrDefault(uuid, 0L) + Math.max(0, deltaSeconds);
        seconds.put(uuid, total);
        dirty = true;
        return total;
    }

    synchronized void setSeconds(UUID uuid, long total) {
        seconds.put(uuid, Math.max(0, total));
        dirty = true;
    }

    synchronized void clearAwarded(UUID uuid, Set<String> keepIds) {
        Set<String> ids = awarded.getOrDefault(uuid, Set.of());
        Set<String> cleaned = new HashSet<>(ids);
        cleaned.retainAll(keepIds);
        if (!cleaned.equals(ids)) {
            awarded.put(uuid, cleaned);
            dirty = true;
        }
    }

    synchronized boolean markAwarded(UUID uuid, String rankId) {
        Set<String> ids = awarded.computeIfAbsent(uuid, key -> new HashSet<>());
        boolean added = ids.add(rankId);
        if (added) {
            dirty = true;
        }
        return added;
    }

    synchronized long secondsOf(UUID uuid) {
        return seconds.getOrDefault(uuid, 0L);
    }

    synchronized boolean known(UUID uuid) {
        return seconds.containsKey(uuid);
    }

    boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }
}
