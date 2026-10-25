package ru.cherdakmd.chrdkevents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * История мировых событий в {@code events.json} (1.4.160).
 *
 * <p>Что хранится: когда было следующее событие, до какого момента каждое на
 * паузе, сколько раз оно уже шло и последние записи истории. Благодаря этому
 * после перезапуска сервера таймер продолжает с того же места, а игроки не
 * видят «кровавую луну» третий раз за вечер.</p>
 *
 * <p>Само активное событие намеренно не восстанавливается: его эффекты и
 * bossbar жили в памяти, и «продолжить» их после рестарта — значит выдать
 * часть бонуса без объявления. Вместо этого оно закрывается записью в историю,
 * а расписание стартует заново.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}.</p>
 */
public final class EventsStore {

    /** Версия формата файла. */
    public static final int VERSION = 1;
    /** Сколько последних событий хранить: дальше история не нужна. */
    public static final int MAX_HISTORY = 50;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    /**
     * Одна запись истории.
     *
     * @param id       ид события
     * @param name     имя на момент запуска
     * @param kind     вид события
     * @param started  когда началось (мс)
     * @param ended    когда закончилось (мс); 0 — ещё идёт
     * @param duration сколько длилось в секундах (0 — ещё идёт)
     * @param forced   запущено админом, а не расписанием
     */
    public record Entry(String id, String name, String kind, long started, long ended,
                        int duration, boolean forced) {

        public Entry {
            id = id == null ? "" : id;
            name = name == null ? id : name;
            kind = kind == null ? "" : kind;
            started = Math.max(0L, started);
            ended = Math.max(0L, ended);
            duration = Math.max(0, duration);
        }

        /** Событие ещё не закрыто. */
        public boolean open() {
            return ended <= 0L;
        }
    }

    private final Path file;
    private final Logger logger;
    private long nextAt;
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    private final Map<String, Integer> totals = new LinkedHashMap<>();
    private final List<Entry> history = new ArrayList<>();
    private boolean dirty;

    public EventsStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("events.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые поля пропускаются. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            this.nextAt = Math.max(0L, number(data.get("next-at")));
            readCooldowns(data.get("cooldowns"));
            readTotals(data.get("total"));
            readHistory(data.get("history"));
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.warning("[CHRDK_Events] Не удалось прочитать events.json: "
                        + error.getMessage() + " — начну с чистого листа.");
            }
        }
    }

    private void readCooldowns(Object raw) {
        if (!(raw instanceof Map<?, ?> rows)) {
            return;
        }
        for (Map.Entry<?, ?> row : rows.entrySet()) {
            String id = normalizeId(row.getKey());
            if (id.isEmpty()) {
                continue;
            }
            cooldowns.put(id, Math.max(0L, number(row.getValue())));
        }
    }

    private void readTotals(Object raw) {
        if (!(raw instanceof Map<?, ?> rows)) {
            return;
        }
        for (Map.Entry<?, ?> row : rows.entrySet()) {
            String id = normalizeId(row.getKey());
            if (id.isEmpty()) {
                continue;
            }
            totals.put(id, (int) Math.clamp(number(row.getValue()), 0L, Integer.MAX_VALUE));
        }
    }

    private void readHistory(Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String id = normalizeId(map.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            history.add(new Entry(id, string(map.get("name")), string(map.get("kind")),
                    number(map.get("started")), number(map.get("ended")),
                    (int) Math.clamp(number(map.get("duration")), 0L, Integer.MAX_VALUE),
                    Boolean.TRUE.equals(map.get("forced"))));
            if (history.size() >= MAX_HISTORY) {
                break;
            }
        }
        trim();
    }

    /* --------------------------------- мутации -------------------------------- */

    /** Запоминает запуск события. */
    public synchronized void recordStart(WorldEvent event, long now, boolean forced) {
        if (event == null) {
            return;
        }
        closeOpen(now);
        history.add(new Entry(event.id(), event.name(), event.kind().name(), now, 0L, 0, forced));
        totals.merge(event.id(), 1, Integer::sum);
        trim();
        dirty = true;
    }

    /** Запоминает окончание события. */
    public synchronized void recordStop(WorldEvent event, long now) {
        if (event == null) {
            return;
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            Entry entry = history.get(index);
            if (entry.open() && entry.id().equals(event.id())) {
                long ended = Math.max(entry.started(), now);
                int seconds = (int) Math.clamp((ended - entry.started()) / 1_000L, 0L,
                        WorldEvent.MAX_DURATION_SECONDS);
                history.set(index, new Entry(entry.id(), entry.name(), entry.kind(),
                        entry.started(), ended, seconds, entry.forced()));
                dirty = true;
                return;
            }
        }
    }

    /** Запоминает момент следующего запуска. */
    public synchronized void setNextAt(long nextAt) {
        long value = Math.max(0L, nextAt);
        if (this.nextAt != value) {
            this.nextAt = value;
            dirty = true;
        }
    }

    /** Запоминает паузы; вызывается после остановки события. */
    public synchronized void setCooldowns(Map<String, Long> source) {
        if (source == null) {
            return;
        }
        cooldowns.clear();
        cooldowns.putAll(source);
        dirty = true;
    }

    /** Закрывает незавершённую запись (перезапуск сервера во время события). */
    public synchronized void closeOpen(long now) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Entry entry = history.get(index);
            if (entry.open()) {
                long ended = Math.max(entry.started(), now);
                int seconds = (int) Math.clamp((ended - entry.started()) / 1_000L, 0L,
                        WorldEvent.MAX_DURATION_SECONDS);
                history.set(index, new Entry(entry.id(), entry.name(), entry.kind(),
                        entry.started(), ended, seconds, entry.forced()));
                dirty = true;
            }
        }
    }

    private void trim() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /* --------------------------------- доступ --------------------------------- */

    public synchronized long nextAt() {
        return nextAt;
    }

    public synchronized Map<String, Long> cooldowns() {
        return Map.copyOf(cooldowns);
    }

    /** Сколько раз событие шло. */
    public synchronized int total(String id) {
        return totals.getOrDefault(normalizeId(id), 0);
    }

    /** Сколько всего событий было. */
    public synchronized int totalAll() {
        int total = 0;
        for (int value : totals.values()) {
            total += value;
        }
        return total;
    }

    /** История от старых к новым; копия. */
    public synchronized List<Entry> history() {
        return List.copyOf(history);
    }

    /** Последнее событие из истории или {@code null}. */
    public synchronized Entry last() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* -------------------------------- запись ---------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        data.put("next-at", nextAt);
        data.put("cooldowns", new LinkedHashMap<>(cooldowns));
        data.put("total", new LinkedHashMap<>(totals));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry entry : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("name", entry.name());
            row.put("kind", entry.kind());
            row.put("started", entry.started());
            row.put("ended", entry.ended());
            row.put("duration", entry.duration());
            row.put("forced", entry.forced());
            rows.add(row);
        }
        data.put("history", rows);
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
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Events]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String normalizeId(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String string(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static long number(Object raw) {
        return raw instanceof Number value ? value.longValue() : 0L;
    }
}
