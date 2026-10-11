package ru.cherdakmd.chrdkdaily;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Прогресс игроков (daily-data.json): последний забранный день (сутки UTC), текущий стрик и
 * счётчик наград. Тот же паттерн записи, что в магазине и рефералке.
 */
public final class DailyStore {

    /** Прогресс одного игрока. */
    public static final class Progress {
        public long lastClaimDay = -1; // сутки UTC, -1 = ещё ни разу
        public int streak;
        public int totalClaims;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Progress> players = new LinkedHashMap<>();
    private boolean dirty;

    public DailyStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("daily-data.json");
        this.logger = logger;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null || !(data.get("players") instanceof Map<?, ?> map)) return;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> row)) continue;
                try {
                    Progress progress = new Progress();
                    progress.lastClaimDay = longOf(row.get("lastClaimDay"), -1);
                    progress.streak = (int) longOf(row.get("streak"), 0);
                    progress.totalClaims = (int) longOf(row.get("totalClaims"), 0);
                    players.put(UUID.fromString(String.valueOf(entry.getKey())), progress);
                } catch (Exception ignored) {
                    // повреждённая запись
                }
            }
            logger.info("[DailyRewards] Загружено прогрессов: " + players.size() + ".");
        } catch (Exception e) {
            logger.severe("[DailyRewards] Не удалось прочитать " + file + ": " + e.getMessage());
        }
    }

    /* ------------------------------- стрик-логика ------------------------------- */

    /** Номер суток UTC для метки времени. */
    public static long dayOf(long epochMillis) {
        return Math.floorDiv(epochMillis, 86_400_000L);
    }

    public synchronized Progress progressOf(UUID uuid) {
        return players.computeIfAbsent(uuid, key -> new Progress());
    }

    /** Можно ли забрать награду сегодня. */
    public synchronized boolean canClaim(UUID uuid, long nowMillis) {
        return progressOf(uuid).lastClaimDay < dayOf(nowMillis);
    }

    /**
     * Забирает награду: стрик продлевается при claim в следующий календарный день, иначе
     * сбрасывается в 1. Возвращает новый стрик (или -1, если сегодня уже взято).
     * Чистая арифметика — покрыта юнит-тестами.
     */
    public synchronized int claim(UUID uuid, long nowMillis) {
        Progress progress = progressOf(uuid);
        long today = dayOf(nowMillis);
        if (progress.lastClaimDay >= today) return -1;
        progress.streak = progress.lastClaimDay == today - 1 ? progress.streak + 1 : 1;
        progress.lastClaimDay = today;
        progress.totalClaims++;
        dirty = true;
        return progress.streak;
    }

    /** Административная установка стрика (последний день обнуляется — взять снова можно завтра… точнее сразу). */
    public synchronized void setStreak(UUID uuid, int streak) {
        Progress progress = progressOf(uuid);
        progress.streak = Math.max(0, streak);
        progress.lastClaimDay = -1;
        dirty = true;
    }

    /* ------------------------------- сохранение ------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> playersCopy = new LinkedHashMap<>();
        players.forEach((uuid, progress) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("lastClaimDay", progress.lastClaimDay);
            fields.put("streak", progress.streak);
            fields.put("totalClaims", progress.totalClaims);
            playersCopy.put(uuid.toString(), fields);
        });
        data.put("players", playersCopy);
        dirty = false;
        return GSON.toJson(data);
    }

    public void saveNow() {
        if (!dirty && Files.isRegularFile(file)) return;
        String json = snapshotJson();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (IOException e) {
            logger.severe("[DailyRewards] Не удалось записать " + file + ": " + e.getMessage());
            dirty = true;
        }
    }

    private static long longOf(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
