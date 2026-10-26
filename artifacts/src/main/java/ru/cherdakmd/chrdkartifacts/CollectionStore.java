package ru.cherdakmd.chrdkartifacts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Коллекция артефактов игроков (`collection.json`).
 *
 * <p>Формат: {@code {"version":1,"players":{"<uuid>":{"discovered":[…],"
 * claimed":[25,50],"firstFound":…,"lastFound":…}}}}. Ключ — UUID строкой:
 * ник можно сменить, а открытия должны остаться.</p>
 *
 * <p>Паттерн записи — как у остальных аддонов: мутации на главном потоке,
 * периодический snapshot {@link #snapshotJson()} и асинхронная запись
 * {@link #saveNow()} из планировщика плагина. Синхронизация — на самом
 * хранилище, потому что запись идёт с другого потока.</p>
 *
 * <p>Класс чистый (Gson + NIO, без Bukkit), поэтому проверяется юнит-тестами.
 * Незнакомые и удалённые из каталога иды при чтении сохраняются, но в
 * {@link #count(String)} не учитываются: прогресс не может превысить каталог,
 * даже если в старом файле остались записи вырезанных артефактов.</p>
 */
public final class CollectionStore {

    /** Прогресс одного игрока. */
    public static final class Progress {
        public final Set<String> discovered = new LinkedHashSet<>();
        public final Set<Integer> claimed = new LinkedHashSet<>();
        public long firstFound;
        public long lastFound;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, Progress> players = new LinkedHashMap<>();
    private boolean dirty;

    public CollectionStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("collection.json");
        this.logger = logger;
    }

    /** Путь файла — для логов и диагностики. */
    public Path file() {
        return file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            Object rawPlayers = data.get("players");
            if (!(rawPlayers instanceof Map<?, ?> rows)) {
                return;
            }
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                Progress progress = new Progress();
                readIds(row.get("discovered")).forEach(progress.discovered::add);
                readPercents(row.get("claimed")).forEach(progress.claimed::add);
                progress.firstFound = longOf(row.get("firstFound"));
                progress.lastFound = longOf(row.get("lastFound"));
                players.put(String.valueOf(entry.getKey()), progress);
            }
            logger.info("[CHRDK_Artifacts] Коллекция загружена: " + players.size()
                    + " игрок(ов).");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Artifacts] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    /**
     * Отмечает артефакт открытым.
     *
     * @return {@code true} — артефакт открыт впервые
     */
    public synchronized boolean discover(String playerKey, String artifactId, long nowMillis) {
        if (playerKey == null || artifactId == null || ArtifactRegistry.byId(artifactId) == null) {
            return false;
        }
        Progress progress = players.computeIfAbsent(playerKey, key -> new Progress());
        if (!progress.discovered.add(artifactId)) {
            return false;
        }
        if (progress.firstFound == 0L) {
            progress.firstFound = nowMillis;
        }
        progress.lastFound = nowMillis;
        dirty = true;
        return true;
    }

    public synchronized boolean isDiscovered(String playerKey, String artifactId) {
        Progress progress = players.get(playerKey);
        return progress != null && progress.discovered.contains(artifactId);
    }

    /** Число открытых артефактов, которые всё ещё есть в каталоге. */
    public synchronized int count(String playerKey) {
        Progress progress = players.get(playerKey);
        if (progress == null) {
            return 0;
        }
        int valid = 0;
        for (String id : progress.discovered) {
            if (ArtifactRegistry.byId(id) != null) {
                valid++;
            }
        }
        return Math.min(valid, ArtifactRegistry.size());
    }

    /** Копия открытых идов (для GUI и тестов). */
    public synchronized Set<String> discovered(String playerKey) {
        Progress progress = players.get(playerKey);
        return progress == null ? Set.of() : Set.copyOf(progress.discovered);
    }

    public synchronized Set<Integer> claimed(String playerKey) {
        Progress progress = players.get(playerKey);
        return progress == null ? Set.of() : Set.copyOf(progress.claimed);
    }

    /** Отмечает награду за порог выданной. */
    public synchronized boolean markClaimed(String playerKey, int percent) {
        Progress progress = players.computeIfAbsent(playerKey, key -> new Progress());
        if (!progress.claimed.add(percent)) {
            return false;
        }
        dirty = true;
        return true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        Map<String, Object> rows = new LinkedHashMap<>();
        players.forEach((key, progress) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("discovered", new ArrayList<>(progress.discovered));
            fields.put("claimed", new ArrayList<>(progress.claimed));
            fields.put("firstFound", progress.firstFound);
            fields.put("lastFound", progress.lastFound);
            rows.put(key, fields);
        });
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл; при ошибке возвращает флаг «грязно», чтобы повторить позже. */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty && Files.isRegularFile(file)) {
                return;
            }
            json = snapshotJson();
        }
        write(json);
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    public void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Artifacts]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }


    private static List<String> readIds(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element != null && !String.valueOf(element).isBlank()) {
                    out.add(String.valueOf(element).trim());
                }
            }
        }
        return out;
    }

    private static List<Integer> readPercents(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Number number) {
                    out.add(Math.max(1, Math.min(100, number.intValue())));
                }
            }
        }
        return out;
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
