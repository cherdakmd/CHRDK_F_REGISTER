package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Память стартового комплекта: {@code starter-kits.json} в папке CHRDK_Core.
 *
 * <pre>{@code
 * { "version": 1, "given": ["uuid"], "pending": ["uuid"] }
 * }</pre>
 *
 * <p>Ключ — UUID, а не ник: смена ника не должна дарить комплект заново.
 * <b>pending</b> — новичок зашёл, но ещё не завершил авторизацию; <b>given</b> —
 * комплект выдан (или игрок играл до обновления, и стартовый подарок ему не
 * положен). Запись атомарная: обрыв сервера не оставляет битый JSON.</p>
 */
public final class StarterKitStore {

    private static final int VERSION = 1;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Set<String> given = new LinkedHashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private volatile boolean dirty = false;

    public StarterKitStore(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("starter-kits.json");
        this.logger = logger;
    }

    public static String key(UUID id) {
        return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
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
            // «Выдан» важнее «ожидает»: ручная правка файла не дарит комплект дважды.
            pending.removeAll(given);
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Не удалось прочитать starter-kits.json: " + exception
                    + ". Начнём с чистого листа — новички получат комплект при первом входе.");
        }
    }

    private static void readInto(Object raw, Set<String> target) {
        if (!(raw instanceof Iterable<?> list)) {
            return;
        }
        for (Object entry : list) {
            String value = String.valueOf(entry).trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                target.add(value);
            }
        }
    }

    public synchronized boolean isGiven(String key) {
        return given.contains(key);
    }

    public synchronized boolean isPending(String key) {
        return pending.contains(key);
    }

    public synchronized void markPending(String key) {
        if (key == null || key.isEmpty() || given.contains(key)) {
            return;
        }
        if (pending.add(key)) {
            dirty = true;
            saveNow();
        }
    }

    /** Комплект выдан (или игрок не новичок): повторно не выдаём. */
    public synchronized void markGiven(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        boolean changed = pending.remove(key);
        changed |= given.add(key);
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

    /** JSON-снимок для записи и для тестов. */
    public synchronized String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("given", new ArrayList<>(given));
        root.put("pending", new ArrayList<>(pending));
        return gson.toJson(root);
    }

    public void saveNow() {
        String json;
        synchronized (this) {
            json = snapshotJson();
            dirty = false;
        }
        Path parent = file.getParent();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.severe("Не удалось сохранить starter-kits.json: " + exception);
            dirty = true;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                //temp уже перемещён или недоступен — файл given всё равно записан.
            }
        }
    }

    public Path file() {
        return file;
    }
}
