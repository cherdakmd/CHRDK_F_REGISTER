package ru.cherdakmd.chrdkachievements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Данные достижений (`achievements.json`).
 *
 * <p>Формат: {@code {"version":1,"players":{"<uuid>":{"counters":{"mobs-killed":42},
 * "unlocked":[…],"claimed":[…],"badge":"veteran"}}}}. Ключ — UUID: ник можно
 * сменить, прогресс при этом не теряется.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * периодический {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}.
 * Синхронизация — на хранилище, потому что запись идёт с другого потока.</p>
 *
 * <p>Класс чистый (Gson + NIO): проверяется юнит-тестами без Bukkit.</p>
 */
public final class AchievementStore {

    /** Прогресс одного игрока. */
    public static final class PlayerData {
        public final Map<AchievementMetric, Long> counters = new LinkedHashMap<>();
        public final Set<String> unlocked = new LinkedHashSet<>();
        public final Set<String> claimed = new LinkedHashSet<>();
        public String badge;

        public long value(AchievementMetric metric) {
            return counters.getOrDefault(metric, 0L);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, PlayerData> players = new LinkedHashMap<>();
    private boolean dirty;

    public AchievementStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("achievements.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null || !(data.get("players") instanceof Map<?, ?> rows)) {
                return;
            }
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> row)) {
                    continue;
                }
                PlayerData player = new PlayerData();
                if (row.get("counters") instanceof Map<?, ?> counters) {
                    for (Map.Entry<?, ?> counter : counters.entrySet()) {
                        AchievementMetric metric = AchievementMetric.byId(String.valueOf(counter.getKey()));
                        if (metric != null && counter.getValue() instanceof Number number) {
                            player.counters.put(metric, Math.max(0L, number.longValue()));
                        }
                    }
                }
                readStrings(row.get("unlocked")).forEach(player.unlocked::add);
                readStrings(row.get("claimed")).forEach(player.claimed::add);
                Object badge = row.get("badge");
                player.badge = badge == null || String.valueOf(badge).isBlank()
                        ? null : String.valueOf(badge);
                players.put(String.valueOf(entry.getKey()), player);
            }
            logger.info("[CHRDK_Achievements] Загружено: " + players.size()
                    + " профил(ей) достижений.");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Achievements] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    public synchronized long value(String playerKey, AchievementMetric metric) {
        PlayerData data = players.get(playerKey);
        return data == null || metric == null ? 0L : data.value(metric);
    }

    /** Прибавляет к счётчику; отрицательные приращения игнорируются. */
    public synchronized long add(String playerKey, AchievementMetric metric, long amount) {
        if (playerKey == null || metric == null || amount <= 0L) {
            return value(playerKey, metric);
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        long next = data.value(metric) + amount;
        data.counters.put(metric, next);
        dirty = true;
        return next;
    }

    /** Ставит счётчик (не уменьшая уже накопленное значение). */
    public synchronized long raise(String playerKey, AchievementMetric metric, long atLeast) {
        if (playerKey == null || metric == null) {
            return 0L;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        long current = data.value(metric);
        if (atLeast <= current) {
            return current;
        }
        data.counters.put(metric, atLeast);
        dirty = true;
        return atLeast;
    }

    public synchronized boolean isUnlocked(String playerKey, String achievementId) {
        PlayerData data = players.get(playerKey);
        return data != null && data.unlocked.contains(achievementId);
    }

    public synchronized Set<String> unlocked(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.unlocked);
    }

    /** Отмечает достижение открытым. {@code true} — открылось впервые. */
    public synchronized boolean unlock(String playerKey, String achievementId) {
        if (playerKey == null || achievementId == null) {
            return false;
        }
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!data.unlocked.add(achievementId)) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Отмечает награду выданной (страховка от двойной выплаты). */
    public synchronized boolean markClaimed(String playerKey, String achievementId) {
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        if (!data.claimed.add(achievementId)) {
            return false;
        }
        dirty = true;
        return true;
    }

    public synchronized Set<String> claimed(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? Set.of() : Set.copyOf(data.claimed);
    }

    public synchronized String badge(String playerKey) {
        PlayerData data = players.get(playerKey);
        return data == null ? null : data.badge;
    }

    /** Ставит или снимает бейдж ({@code null} — снять). */
    public synchronized void setBadge(String playerKey, String badgeId) {
        PlayerData data = players.computeIfAbsent(playerKey, key -> new PlayerData());
        data.badge = badgeId == null || badgeId.isBlank() ? null : badgeId;
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        Map<String, Object> rows = new LinkedHashMap<>();
        players.forEach((key, player) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            Map<String, Object> counters = new LinkedHashMap<>();
            player.counters.forEach((metric, value) -> counters.put(metric.id(), value));
            fields.put("counters", counters);
            fields.put("unlocked", new ArrayList<>(player.unlocked));
            fields.put("claimed", new ArrayList<>(player.claimed));
            fields.put("badge", player.badge);
            rows.put(key, fields);
        });
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, если есть изменения (или его ещё нет). */
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
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            move(temp, file);
        } catch (IOException exception) {
            logger.severe("[CHRDK_Achievements] Не удалось записать " + file + ": "
                    + exception.getMessage());
            synchronized (this) {
                dirty = true;
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // временный файл безвреден и перезапишется в следующий раз
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> readStrings(Object raw) {
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
}
