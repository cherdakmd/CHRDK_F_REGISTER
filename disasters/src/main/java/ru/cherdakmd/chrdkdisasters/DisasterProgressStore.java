package ru.cherdakmd.chrdkdisasters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Память прогресса катастроф: {@code disasters-progress.json} в папке модуля.
 *
 * <pre>{@code
 * { "version": 1, "players": { "<uuid>": { "shards": 12, "xp": 340, ... } } }
 * }</pre>
 *
 * <p>Ключ — UUID, а не ник. Запись атомарная через {@code .tmp}: обрыв сервера
 * не оставляет битый JSON и не теряет осколки игроков. Профили создаются в
 * памяти по мере обращений, а на диск попадают при начислении, покупке и
 * корректном выключении плагина.</p>
 */
public final class DisasterProgressStore {

    private static final int VERSION = 1;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Map<String, DisasterProfile> players = new LinkedHashMap<>();
    private volatile boolean dirty;

    public DisasterProgressStore(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("disasters-progress.json");
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
            players.clear();
            players.putAll(DisasterProfile.readPlayers(root.get("players")));
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("[CHRDK_Disasters] не удалось прочитать disasters-progress.json: "
                    + exception + ". Начнём с чистого листа.");
        }
    }

    /** Профиль игрока; создаётся в памяти, но файл не пишется до начисления. */
    public synchronized DisasterProfile profile(UUID id) {
        String key = key(id);
        if (key.isEmpty()) {
            return new DisasterProfile();
        }
        return players.computeIfAbsent(key, ignored -> new DisasterProfile());
    }

    public synchronized int size() {
        return players.size();
    }

    public synchronized String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("players", DisasterProfile.writePlayers(players));
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
            logger.severe("[CHRDK_Disasters] не удалось сохранить disasters-progress.json: "
                    + exception);
            dirty = true;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // temp уже перемещён; снимок на диске цел.
            }
        }
    }

    public Path file() {
        return file;
    }
}
