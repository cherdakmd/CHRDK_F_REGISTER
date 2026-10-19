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
 * Память книги-путеводителя: {@code guide-data.json} —
 * <pre>{@code
 * { "version": 1, "given": ["steve"], "pending": ["alex"] }
 * }</pre>
 *
 * <p><b>pending</b> — игрок впервые зашёл на сервер и ещё не получил книгу (например, вышел
 * до регистрации). <b>given</b> — книга выдана, либо игрок играл до обновления: ветеранам
 * книгу при входе не навязываем (они могут взять её командой {@code /guide}). Ники хранятся
 * в нижнем регистре.</p>
 *
 * <p>Файл пишется сразу при изменении: события редкие (первый вход, выдача книги).</p>
 */
public final class GuideStore {

    private static final int VERSION = 1;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Set<String> given = new LinkedHashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private volatile boolean dirty = false;

    public GuideStore(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("guide-data.json");
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
            readInto(root.get("given"), given);
            readInto(root.get("pending"), pending);
            given.removeAll(pending);
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Не удалось прочитать guide-data.json: " + exception
                    + ". Начнём с чистого листа — новички получат книгу при входе.");
        }
    }

    private static void readInto(Object raw, Set<String> target) {
        if (!(raw instanceof Iterable<?> list)) {
            return;
        }
        for (Object entry : list) {
            String name = key(String.valueOf(entry));
            if (!name.isEmpty()) {
                target.add(name);
            }
        }
    }

    public synchronized boolean isGiven(String playerKey) {
        return given.contains(playerKey);
    }

    public synchronized boolean isPending(String playerKey) {
        return pending.contains(playerKey);
    }

    /** Новый игрок: книга ещё не выдана. */
    public synchronized void markPending(String playerKey) {
        if (playerKey == null || playerKey.isEmpty() || given.contains(playerKey)) {
            return;
        }
        if (pending.add(playerKey)) {
            dirty = true;
            saveNow();
        }
    }

    /** Книга выдана (или игрок не новичок): повторно не выдаём. */
    public synchronized void markGiven(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) {
            return;
        }
        boolean changed = pending.remove(playerKey);
        changed |= given.add(playerKey);
        if (changed) {
            dirty = true;
            saveNow();
        }
    }

    public synchronized int givenCount() {
        return given.size();
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
        root.put("given", new java.util.ArrayList<>(given));
        root.put("pending", new java.util.ArrayList<>(pending));
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
            logger.severe("Не удалось сохранить guide-data.json: " + exception);
            dirty = true;
        }
    }

    /** Путь файла (для диагностики и тестов). */
    public Path file() {
        return file;
    }
}
