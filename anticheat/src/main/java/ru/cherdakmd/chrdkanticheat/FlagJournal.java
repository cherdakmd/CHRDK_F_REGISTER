package ru.cherdakmd.chrdkanticheat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Журнал доказательств античита: дополняемый {@code flags.jsonl} с ежедневной
 * ротацией в {@code flags-YYYYMMDD.jsonl} и чисткой архивов старше keep-days.
 *
 * <p>Повреждённая строка не ломает чтение: она пропускается, остальные записи
 * остаются доступными. День архива — по UTC (как у выплат ядра). Идентификаторы
 * сквозные по сохранённым записям: после рестарта нумерация продолжается.</p>
 */
final class FlagJournal {

    static final String CURRENT_FILE = "flags.jsonl";
    private static final Pattern ARCHIVE = Pattern.compile("flags-(\\d{8})\\.jsonl");
    private static final DateTimeFormatter ARCHIVE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path dir;
    private final int keepDays;
    private final LongSupplier clock;
    private final Gson gson = new Gson();
    private long lastId;
    private LocalDate activeDay;

    FlagJournal(Path dir, int keepDays, LongSupplier clock) throws IOException {
        this.dir = dir;
        this.keepDays = keepDays;
        this.clock = clock;
        Files.createDirectories(dir);
        this.activeDay = lastRecordDay(current()).orElse(today());
        this.lastId = countRecords();
    }

    /** Дописывает запись (id назначается журналом) и возвращает её с id. */
    synchronized FlagRecord append(FlagRecord draft) throws IOException {
        LocalDate day = Instant.ofEpochMilli(draft.time()).atZone(ZoneOffset.UTC).toLocalDate();
        if (!day.equals(activeDay)) {
            rotate(activeDay);
            cleanup();
            activeDay = day;
        }
        FlagRecord saved = draft.withId(++lastId);
        Files.writeString(current(), gson.toJson(toJson(saved)) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        return saved;
    }

    /** Все записи журнала (текущий файл + архивы), новые в конце. */
    synchronized List<FlagRecord> all() {
        List<FlagRecord> records = new ArrayList<>();
        for (Path file : orderedFiles()) {
            readFile(file, records);
        }
        records.sort(Comparator.comparingLong(FlagRecord::time));
        return records;
    }

    /** Записи одного игрока (ник без учёта регистра или UUID), новые в конце. */
    synchronized List<FlagRecord> history(String playerOrUuid) {
        String needle = playerOrUuid.toLowerCase(java.util.Locale.ROOT);
        List<FlagRecord> records = new ArrayList<>();
        for (FlagRecord record : all()) {
            if (record.player().toLowerCase(java.util.Locale.ROOT).equals(needle)
                    || record.uuid().toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                records.add(record);
            }
        }
        return records;
    }

    /** Игроки со срабатываниями не раньше момента: по убыванию числа флагов. */
    synchronized List<TopEntry> topSince(long sinceEpochMs) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Set<String>> checks = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (FlagRecord record : all()) {
            if (record.time() < sinceEpochMs) {
                continue;
            }
            String key = record.player().toLowerCase(java.util.Locale.ROOT);
            names.put(key, record.player());
            totals.computeIfAbsent(key, ignored -> new long[1])[0]++;
            checks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(record.check());
        }
        List<TopEntry> entries = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            entries.add(new TopEntry(names.get(entry.getKey()), entry.getValue()[0],
                    List.copyOf(checks.get(entry.getKey()))));
        }
        entries.sort(Comparator.comparingLong(TopEntry::total).reversed());
        return entries;
    }

    /** Удаляет архивы старше keep-days (по дате в имени файла). */
    synchronized int cleanup() {
        LocalDate cutoff = today().minusDays(keepDays);
        int removed = 0;
        for (Path file : archives()) {
            LocalDate day = archiveDay(file);
            if (day != null && day.isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException error) {
                    // Недоступный файл не должен ронять запуск: вернёмся к нему при следующей чистке.
                }
            }
        }
        return removed;
    }

    /** Карточка нарушителя по его записям: итоги, топ-чеки и последние локации. */
    static Summary summarize(List<FlagRecord> records) {
        if (records.isEmpty()) {
            return new Summary(0, 0, 0, Map.of(), Map.of(), List.of());
        }
        Map<String, Long> checkCounts = new LinkedHashMap<>();
        Map<String, Integer> checkMaxVl = new LinkedHashMap<>();
        Set<String> places = new LinkedHashSet<>();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (FlagRecord record : records) {
            first = Math.min(first, record.time());
            last = Math.max(last, record.time());
            checkCounts.merge(record.check(), 1L, Long::sum);
            checkMaxVl.merge(record.check(), record.vl(), Math::max);
            if (!record.world().isEmpty()) {
                String place = String.format(java.util.Locale.ROOT, "%s %d %d %d",
                        record.world(), (int) record.x(), (int) record.y(), (int) record.z());
                // Уникальные локации в порядке последнего посещения.
                places.remove(place);
                places.add(place);
            }
        }
        List<String> recentPlaces = new ArrayList<>(places);
        return new Summary(records.size(), first, last, Map.copyOf(checkCounts),
                Map.copyOf(checkMaxVl),
                List.copyOf(recentPlaces.subList(Math.max(0, recentPlaces.size() - 5), recentPlaces.size())));
    }

    /** Строка игрока в топе суток. */
    record TopEntry(String player, long total, List<String> checks) {
    }

    /** Итоги по игроку для карточки нарушителя. */
    record Summary(long total, long first, long last, Map<String, Long> checkCounts,
                   Map<String, Integer> checkMaxVl, List<String> places) {
    }

    // ------------------------------------------------------------------ файлы

    private Path current() {
        return dir.resolve(CURRENT_FILE);
    }

    private List<Path> orderedFiles() {
        List<Path> files = new ArrayList<>(archives());
        files.sort(Comparator.comparing((Path path) -> path.getFileName().toString()));
        Path current = current();
        if (Files.exists(current)) {
            files.add(current);
        }
        return files;
    }

    private List<Path> archives() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "flags-*.jsonl")) {
            for (Path file : stream) {
                if (ARCHIVE.matcher(file.getFileName().toString()).matches()) {
                    files.add(file);
                }
            }
        } catch (IOException error) {
            return List.copyOf(files);
        }
        return files;
    }

    private void rotate(LocalDate day) throws IOException {
        Path current = current();
        if (!Files.exists(current)) {
            return;
        }
        Path archive = dir.resolve("flags-" + ARCHIVE_DAY.format(day) + ".jsonl");
        if (Files.exists(archive)) {
            Files.writeString(archive, Files.readString(current, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            Files.delete(current);
            return;
        }
        Files.move(current, archive, StandardCopyOption.REPLACE_EXISTING);
    }

    private LocalDate today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private java.util.Optional<LocalDate> lastRecordDay(Path file) {
        if (!Files.exists(file)) {
            return java.util.Optional.empty();
        }
        Long lastTime = null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                FlagRecord record = fromJson(line);
                if (record != null) {
                    lastTime = record.time();
                }
            }
        } catch (IOException error) {
            return java.util.Optional.empty();
        }
        if (lastTime == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                Instant.ofEpochMilli(lastTime).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private long countRecords() {
        long count = 0;
        for (Path file : orderedFiles()) {
            count += readCount(file);
        }
        return count;
    }

    private void readFile(Path file, List<FlagRecord> out) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                FlagRecord record = fromJson(line);
                if (record != null) {
                    out.add(record);
                }
            }
        } catch (IOException error) {
            // Нечитаемый файл пропускаем: журнал не должен ронять команды модераторов.
        }
    }

    private long readCount(Path file) {
        long count = 0;
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (fromJson(line) != null) {
                    count++;
                }
            }
        } catch (IOException error) {
            return count;
        }
        return count;
    }

    private LocalDate archiveDay(Path file) {
        java.util.regex.Matcher matcher = ARCHIVE.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1), ARCHIVE_DAY);
        } catch (RuntimeException error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ json

    private JsonObject toJson(FlagRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("id", record.id());
        json.addProperty("time", record.time());
        json.addProperty("player", record.player());
        json.addProperty("uuid", record.uuid());
        json.addProperty("check", record.check());
        json.addProperty("vl", record.vl());
        json.addProperty("ping", record.ping());
        json.addProperty("tps", record.tps());
        json.addProperty("world", record.world());
        json.addProperty("x", record.x());
        json.addProperty("y", record.y());
        json.addProperty("z", record.z());
        json.addProperty("verbose", record.verbose());
        return json;
    }

    private FlagRecord fromJson(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonObject json = gson.fromJson(trimmed, JsonObject.class);
            if (json == null || !json.has("time") || !json.has("player") || !json.has("check")) {
                return null;
            }
            return new FlagRecord(
                    json.has("id") ? json.get("id").getAsLong() : 0,
                    json.get("time").getAsLong(),
                    json.get("player").getAsString(),
                    json.has("uuid") ? json.get("uuid").getAsString() : "",
                    json.get("check").getAsString(),
                    json.has("vl") ? json.get("vl").getAsInt() : 0,
                    json.has("ping") ? json.get("ping").getAsInt() : -1,
                    json.has("tps") ? json.get("tps").getAsDouble() : 0,
                    json.has("world") ? json.get("world").getAsString() : "",
                    json.has("x") ? json.get("x").getAsDouble() : 0,
                    json.has("y") ? json.get("y").getAsDouble() : 0,
                    json.has("z") ? json.get("z").getAsDouble() : 0,
                    json.has("verbose") ? json.get("verbose").getAsString() : "");
        } catch (RuntimeException error) {
            return null;
        }
    }
}
