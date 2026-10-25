package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Память случайного спавна: {@code spawn-data.json} —
 * <pre>{@code
 * { "version": 1, "done": ["steve"], "pending": { "alex": 1690000000000 } }
 * }</pre>
 *
 * <p><b>pending</b> — игрок впервые зашёл на сервер и ещё не получил свою случайную точку
 * (например, вышел до регистрации). <b>done</b> — точка уже выдана (или игрок пришёл на
 * сервер раньше этой версии и рандомить его задним числом нельзя). Ники хранятся в нижнем
 * регистре: смена регистра не создаёт второго «первого захода».</p>
 *
 * <p>Запись синхронная и происходит только при изменениях (первый вход нового игрока,
 * выдача точки) — файл маленький, а события редкие. Паттерн snapshot/saveNow общий с
 * остальными хранилищами репозитория.</p>
 */
public final class SpawnStore {

    private static final int VERSION = 1;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Set<String> done = new LinkedHashSet<>();
    private final Map<String, Long> pending = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public SpawnStore(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("spawn-data.json");
        this.logger = logger;
    }

    /** Ключ хранения: ник без регистра и пробелов по краям. */
    public static String key(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(reader, LinkedHashMap.class);
            if (root == null) {
                return;
            }
            Object rawDone = root.get("done");
            if (rawDone instanceof Iterable<?> list) {
                for (Object entry : list) {
                    String name = key(String.valueOf(entry));
                    if (!name.isEmpty()) {
                        done.add(name);
                    }
                }
            }
            Object rawPending = root.get("pending");
            if (rawPending instanceof Map<?, ?> map) {
                map.forEach((name, moment) -> {
                    String normalized = key(String.valueOf(name));
                    if (normalized.isEmpty()) {
                        return;
                    }
                    long since = moment instanceof Number number ? number.longValue() : 0L;
                    pending.put(normalized, since);
                });
            }
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Не удалось прочитать spawn-data.json: " + exception
                    + ". Начнём с чистого листа — уже игравших не рандомим повторно "
                    + "(они помечаются при следующем входе).");
        }
    }

    public synchronized boolean isDone(String playerKey) {
        return done.contains(playerKey);
    }

    public synchronized boolean isPending(String playerKey) {
        return pending.containsKey(playerKey);
    }

    /** Новый игрок: точка ещё не выдана, но и не «закрыта» навсегда. */
    public synchronized void markPending(String playerKey, long nowMillis) {
        if (playerKey == null || playerKey.isEmpty() || done.contains(playerKey)) {
            return;
        }
        if (pending.put(playerKey, nowMillis) == null) {
            dirty = true;
            saveNow();
        }
    }

    /** Точка выдана (или рандом не положен): повторно игрока не переносим. */
    public synchronized void markDone(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) {
            return;
        }
        boolean changed = pending.remove(playerKey) != null;
        changed |= done.add(playerKey);
        if (changed) {
            dirty = true;
            saveNow();
        }
    }

    /**
     * Чистит «зависшие» заявки: игрок мог зайти один раз и не вернуться.
     *
     * @return сколько записей удалено
     */
    public synchronized int prunePending(long nowMillis, long maxAgeMillis) {
        int removed = 0;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            long since = entry.getValue() == null ? 0L : entry.getValue();
            if (since > 0 && nowMillis - since > maxAgeMillis) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
            saveNow();
        }
        return removed;
    }

    public synchronized int doneCount() {
        return done.size();
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    /** JSON-снимок для записи (и для тестов). */
    public synchronized String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("done", new java.util.ArrayList<>(done));
        root.put("pending", new LinkedHashMap<>(pending));
        return gson.toJson(root);
    }

    public void saveNow() {
        String json;
        synchronized (this) {
            json = snapshotJson();
            dirty = false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (IOException exception) {
            logger.severe("Не удалось сохранить spawn-data.json: " + exception);
            dirty = true;
        }
    }

    /** Путь файла (для диагностики и тестов). */
    public Path file() {
        return file;
    }
}
