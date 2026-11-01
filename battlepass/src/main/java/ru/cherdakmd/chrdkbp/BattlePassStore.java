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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Прогресс сезона: {@code battlepass-data.json}.
 *
 * <pre>{@code
 * { "uuid": { "progress": {"id": N}, "claimed": ["id:FREE"],
 *            "name": "Steve", "wave": 2 } }
 * }</pre>
 *
 * <p>Формат совместим с прежним: старый файл без {@code name}/{@code wave}
 * читается как раньше, а ники и последняя увиденная волна просто появятся при
 * следующей записи. Файл пишется атомарно (временный файл + перенос), поэтому
 * обрыв на середине не портит данные.</p>
 */
final class BattlePassStore {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE =
            new TypeToken<Map<String, Map<String, Object>>>() {
            }.getType();

    /** Данные одного игрока. */
    private static final class Row {
        final Map<String, Integer> progress = new HashMap<>();
        final Set<String> claimed = new HashSet<>();
        String name = "";
        int wave = 0;
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Row> rows = new HashMap<>();
    private volatile boolean dirty;
    private boolean healthy=true;
    synchronized boolean healthy(){return healthy;}

    BattlePassStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("battlepass-data.json");
        this.logger = logger;
    }

    synchronized void load() {
        rows.clear();
        healthy=true;
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
                Row row = new Row();
                if (entry.getValue().get("progress") instanceof Map<?, ?> progress) {
                    for (Map.Entry<?, ?> cell : progress.entrySet()) {
                        if (cell.getValue() instanceof Number number) {
                            row.progress.put(String.valueOf(cell.getKey()), number.intValue());
                        }
                    }
                }
                if (entry.getValue().get("claimed") instanceof Iterable<?> marks) {
                    for (Object mark : marks) {
                        row.claimed.add(String.valueOf(mark));
                    }
                }
                Object rawName = entry.getValue().get("name");
                if (rawName != null) {
                    row.name = String.valueOf(rawName);
                }
                if (entry.getValue().get("wave") instanceof Number wave) {
                    row.wave = wave.intValue();
                }
                rows.put(uuid, row);
            }
        } catch (IOException | RuntimeException exception) {
            healthy=false;
            logger.log(Level.WARNING, "[CHRDK_BattlePass] Не удалось прочитать battlepass-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        saveLocked();
    }

    /** Сохранить, только если что-то менялось (для периодической задачи). */
    synchronized void saveIfDirty() {
        if (dirty) {
            saveLocked();
        }
    }

    private void saveLocked() {
        if(!healthy)return;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            Row row = entry.getValue();
            boolean empty = row.progress.isEmpty() && row.claimed.isEmpty()
                    && row.name.isBlank() && row.wave == 0;
            if (empty) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("progress", row.progress);
            if (!row.claimed.isEmpty()) {
                data.put("claimed", row.claimed.stream().sorted().toList());
            }
            if (!row.name.isBlank()) {
                data.put("name", row.name);
            }
            if (row.wave > 0) {
                data.put("wave", row.wave);
            }
            out.put(entry.getKey().toString(), data);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_BattlePass] Не удалось записать battlepass-data.json: "
                    + exception.getMessage());
        }
    }

    /** Прогресс по заданию растёт только до цели — лишние тиксы не храним. */
    synchronized int addProgress(UUID uuid, String missionId, int delta, int target) {
        Row row = row(uuid);
        int next = Math.min(target, (int)Math.min(Integer.MAX_VALUE,(long)row.progress.getOrDefault(missionId, 0) + Math.max(0, delta)));
        if (!Integer.valueOf(next).equals(row.progress.get(missionId))) {
            row.progress.put(missionId, next);
            dirty = true;
        }
        return next;
    }

    /** Принудительно задать прогресс (админская команда). Значение зажимается в 0..target. */
    synchronized int setProgress(UUID uuid, String missionId, int value, int target) {
        int clamped = Math.max(0, Math.min(target, value));
        Row row = row(uuid);
        row.progress.put(missionId, clamped);
        dirty = true;
        return clamped;
    }

    synchronized int progressOf(UUID uuid, String missionId) {
        Row row = rows.get(uuid);
        return row == null ? 0 : row.progress.getOrDefault(missionId, 0);
    }

    /** Копия прогресса игрока (для очков и меню). */
    synchronized Map<String, Integer> progressMap(UUID uuid) {
        Row row = rows.get(uuid);
        return row == null ? Map.of() : Map.copyOf(row.progress);
    }

    /** Копия отметок о наградах игрока. */
    synchronized Set<String> claimsOf(UUID uuid) {
        Row row = rows.get(uuid);
        return row == null ? Set.of() : Set.copyOf(row.claimed);
    }

    synchronized boolean isClaimed(UUID uuid, String missionId, String track) {
        Row row = rows.get(uuid);
        return row != null && row.claimed.contains(missionId + ":" + track);
    }

    synchronized boolean markClaimed(UUID uuid, String missionId, String track) {
        boolean added = row(uuid).claimed.add(missionId + ":" + track);
        if (added) {
            dirty = true;
        }
        return added;
    }

    synchronized boolean known(UUID uuid) {
        return rows.containsKey(uuid);
    }

    synchronized void reset(UUID uuid) {
        if (rows.remove(uuid) != null) {
            dirty = true;
        }
    }

    /** Сброс всего сезона. Возвращает число затронутых игроков. */
    synchronized int resetAll() {
        int count = rows.size();
        if (count > 0) {
            rows.clear();
            dirty = true;
        }
        return count;
    }

    /** Запомнить последний известный ник (нужен для рейтинга и админ-команд). */
    synchronized void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        Row row = row(uuid);
        if (!name.equals(row.name)) {
            row.name = name;
            dirty = true;
        }
    }

    synchronized String nameOf(UUID uuid) {
        Row row = rows.get(uuid);
        return row == null ? "" : row.name;
    }

    /** Последняя волна, которую игрок уже видел (для оповещения об открытии). */
    synchronized int lastWave(UUID uuid) {
        Row row = rows.get(uuid);
        return row == null ? 0 : row.wave;
    }

    synchronized void setLastWave(UUID uuid, int wave) {
        Row row = row(uuid);
        if (row.wave != wave) {
            row.wave = wave;
            dirty = true;
        }
    }

    synchronized Set<UUID> players() {
        return Set.copyOf(rows.keySet());
    }

    synchronized Map<UUID, Map<String, Integer>> progressSnapshot() {
        Map<UUID, Map<String, Integer>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            snapshot.put(entry.getKey(), Map.copyOf(entry.getValue().progress));
        }
        return snapshot;
    }

    synchronized Map<UUID, Set<String>> claimsSnapshot() {
        Map<UUID, Set<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            snapshot.put(entry.getKey(), Set.copyOf(entry.getValue().claimed));
        }
        return snapshot;
    }

    synchronized Map<UUID, String> namesSnapshot() {
        Map<UUID, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            if (!entry.getValue().name.isBlank()) {
                snapshot.put(entry.getKey(), entry.getValue().name);
            }
        }
        return snapshot;
    }

    /** Сколько записей прогресса и наград всего (для /bpadmin stats). */
    synchronized int progressRecords() {
        int count = 0;
        for (Row row : rows.values()) {
            count += row.progress.size();
        }
        return count;
    }

    synchronized int claimedRecords() {
        int count = 0;
        for (Row row : rows.values()) {
            count += row.claimed.size();
        }
        return count;
    }

    boolean isDirty() {
        return dirty;
    }

    private Row row(UUID uuid) {
        return rows.computeIfAbsent(uuid, key -> new Row());
    }
}
