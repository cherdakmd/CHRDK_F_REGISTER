package ru.cherdakmd.chrdkbosses;

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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Память боссов: кулдауны, счётчики и история в {@code bosses.json} (1.4.162).
 *
 * <p>Сама сессия боя не переживает перезапуск: босс — это событие в мире, а не
 * сохранённое состояние. Переживают только паузы до следующего появления,
 * число побед и последние записи — то, что нельзя пересчитать.</p>
 *
 * <p>Запись общая для репозитория: снимок на серверном потоке, файл — во
 * временный и атомарное переименование. Класс чистый (Gson + NIO) и
 * проверяется тестами без Bukkit.</p>
 */
public final class BossStore {

    /** Версия формата файла. */
    public static final int VERSION = 1;
    /** Сколько записей истории хранить. */
    public static final int MAX_HISTORY = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Одна запись истории: кто, когда и с кем. */
    public record Entry(String id, String name, String killer, int participants, long endedAt) {

        /** Время окончания боя для меню и логов. */
        public String time() {
            return endedAt <= 0 ? "—" : STAMP.format(Instant.ofEpochMilli(endedAt));
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, Long> next = new LinkedHashMap<>();
    private final Map<String, Integer> kills = new LinkedHashMap<>();
    private final List<Entry> history = new ArrayList<>();
    private int total;
    private boolean dirty;

    public BossStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("bosses.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые записи пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            int skipped = 0;
            if (data.get("next") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    String id = id(entry.getKey());
                    long value = number(entry.getValue());
                    if (id.isEmpty() || value <= 0) {
                        skipped++;
                        continue;
                    }
                    next.put(id, value);
                }
            }
            if (data.get("kills") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    String id = id(entry.getKey());
                    if (id.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    kills.put(id, (int) Math.clamp(number(entry.getValue()), 0L,
                            Integer.MAX_VALUE));
                }
            }
            total = (int) Math.clamp(number(data.get("total")), 0L, Integer.MAX_VALUE);
            if (data.get("history") instanceof List<?> rows) {
                for (Object element : rows) {
                    if (!(element instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    if (history.size() >= MAX_HISTORY) {
                        break;
                    }
                    String id = id(row.get("id"));
                    if (id.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    history.add(new Entry(id, string(row.get("name")),
                            string(row.get("killer")),
                            (int) Math.clamp(number(row.get("participants")), 0L, 1_000L),
                            Math.max(0L, number(row.get("ended-at")))));
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Bosses] Пропущено записей bosses.json: " + skipped + ".");
            }
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Bosses] Не удалось прочитать " + file + ": "
                        + error.getMessage() + " — начну с чистого листа.");
            }
        }
    }

    /* -------------------------------- кулдауны -------------------------------- */

    /** Когда босс сможет появиться снова; 0 — можно сразу. */
    public synchronized long nextAt(String bossId) {
        return next.getOrDefault(id(bossId), 0L);
    }

    /** Запоминает паузу; {@code 0} или прошлое — снимает её. */
    public synchronized void setNextAt(String bossId, long moment) {
        String key = id(bossId);
        if (key.isEmpty()) {
            return;
        }
        if (moment <= 0) {
            if (next.remove(key) != null) {
                dirty = true;
            }
            return;
        }
        next.put(key, moment);
        dirty = true;
    }

    /** Снимает паузу — админская команда. */
    public synchronized boolean clearNextAt(String bossId) {
        if (next.remove(id(bossId)) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Снимает все паузы. */
    public synchronized int clearAllNextAt() {
        int size = next.size();
        if (size > 0) {
            next.clear();
            dirty = true;
        }
        return size;
    }

    public synchronized Map<String, Long> nextAll() {
        return Map.copyOf(next);
    }

    /* --------------------------------- счётчики ------------------------------- */

    public synchronized int kills(String bossId) {
        return kills.getOrDefault(id(bossId), 0);
    }

    public synchronized int total() {
        return total;
    }

    /** Сколько побед над всеми боссами вместе. */
    public synchronized int killsAll() {
        int sum = 0;
        for (int value : kills.values()) {
            sum += value;
        }
        return sum;
    }

    /** Записывает победу: счётчики и история. */
    public synchronized void recordKill(BossDefinition boss, String killer, int participants,
                                        long endedAt) {
        if (boss == null) {
            return;
        }
        String id = boss.id();
        kills.merge(id, 1, Integer::sum);
        total++;
        history.add(new Entry(id, boss.name(), killer == null ? "" : killer,
                Math.clamp(participants, 0, 1_000), endedAt));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        dirty = true;
    }

    /** Последние записи, новые в конце. */
    public synchronized List<Entry> history() {
        return List.copyOf(history);
    }

    /** Сбрасывает счётчики и историю, не трогая паузы. */
    public synchronized void resetCounters() {
        if (kills.isEmpty() && history.isEmpty() && total == 0) {
            return;
        }
        kills.clear();
        history.clear();
        total = 0;
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* --------------------------------- запись --------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        if (!next.isEmpty()) {
            data.put("next", new LinkedHashMap<>(next));
        }
        if (!kills.isEmpty()) {
            data.put("kills", new LinkedHashMap<>(kills));
        }
        data.put("total", total);
        if (!history.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Entry entry : history) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", entry.id());
                row.put("name", entry.name());
                if (!entry.killer().isEmpty()) {
                    row.put("killer", entry.killer());
                }
                row.put("participants", entry.participants());
                row.put("ended-at", entry.endedAt());
                rows.add(row);
            }
            data.put("history", rows);
        }
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty) {
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
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Bosses] Не удалось записать " + file + ": "
                        + error.getMessage());
            }
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String id(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String string(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static long number(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }
}
