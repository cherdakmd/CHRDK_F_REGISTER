package ru.cherdakmd.chrdkclasses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Классы игроков в {@code classes.json} (1.4.164).
 *
 * <p>Схема 3 сохраняет старый выбор и, после явной миграции, согласованный
 * собственный прогресс. Не запускает миграцию автоматически: игровой каталог
 * и потребители должны быть готовы до включения новых начислений.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class ClassStore {

    /** Версия формата файла. */
    public static final int VERSION = 3;
    private static final int MAX_FILE_BYTES = 128 * 1024 * 1024;
    private static final long MAX_TIMESTAMP = Long.MAX_VALUE - 366L * 86_400_000L;
    /** Потолок числа игроков в файле: защита от мусора. */
    public static final int MAX_PLAYERS = 200_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Данные одного игрока. Мутировать можно только через методы хранилища. */
    public static final class Player {
        private final UUID id;
        private String name;
        private String classId = "";
        private final Set<String> talents = new LinkedHashSet<>();
        private long changedAt;
        private long respecAt;
        private long abilityAt;
        private long ability2At;
        private ClassDevelopment development;

        Player(UUID id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private boolean dirty;
    private boolean writable = true;
    private boolean loadAttempted;
    private byte[] legacyBytes;
    // Порядок снимков и записей общий для автосохранения и финального flush.
    private final Object writeLock = new Object();

    public ClassStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("classes.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Загружает целиком или отказывает без возможности затереть исходный файл. */
    public synchronized void load() {
        if (loadAttempted || !players.isEmpty() || dirty) {
            throw new IllegalStateException("Повторная загрузка ClassStore не разрешена");
        }
        loadAttempted = true;
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException missing) {
                return;
            }
            if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES) {
                throw new IOException("classes.json не является допустимым обычным файлом");
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("Превышен размер classes.json");
            }
            Map<?, ?> data = ClassStorageJson.read(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            checkKeys(data, Set.of("version", "players"));
            long version = requiredNumber(data.get("version"));
            if (version < 1 || version > VERSION || !(data.get("players") instanceof Map<?, ?> rows)
                    || rows.size() > MAX_PLAYERS) {
                throw new IOException("Неподдерживаемая версия или структура classes.json");
            }
            Map<UUID, Player> loaded = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                UUID id = parseUuid(entry.getKey());
                if (id == null || !(entry.getValue() instanceof Map<?, ?> row)
                        || loaded.containsKey(id)) {
                    throw new IOException("Некорректный или повторный UUID игрока: " + entry.getKey());
                }
                if (version < 3 && row.containsKey("development")) {
                    throw new IOException("Прогресс новой схемы в старом формате");
                }
                loaded.put(id, readPlayer(id, row));
            }
            players.putAll(loaded);
            legacyBytes = version < VERSION ? bytes : null;
        } catch (IOException | RuntimeException error) {
            writable = false;
            String message = "Не удалось безопасно прочитать " + file + ": " + error.getMessage()
                    + ". Запись заблокирована; исходный файл сохранён. Восстановите резервную копию.";
            if (logger != null) {
                logger.severe("[CHRDK_Classes] " + message);
            }
            throw new IllegalStateException(message, error);
        }
    }

    private static Player readPlayer(UUID id, Map<?, ?> row) {
        checkKeys(row, Set.of("name", "class", "talents", "changed-at", "respec-at",
                "ability-at", "ability2-at", "development"));
        String name = text(row, "name", "");
        if (name.length() > 64) {
            throw new IllegalArgumentException("Слишком длинный ник игрока");
        }
        Player player = new Player(id, name);
        player.classId = text(row, "class", "").trim().toLowerCase(Locale.ROOT);
        if (!player.classId.isEmpty() && PlayerClass.parse(player.classId) == null) {
            throw new IllegalArgumentException("Неизвестный класс: " + player.classId);
        }
        if (row.containsKey("talents")) {
            if (!(row.get("talents") instanceof List<?> talents) || talents.size() > 60) {
                throw new IllegalArgumentException("Некорректный список старых талантов");
            }
            for (Object element : talents) {
                if (!(element instanceof String talent) || !player.talents.add(talent)) {
                    throw new IllegalArgumentException("Некорректный или повторный талант");
                }
            }
            // Именно замороженный каталог: обновление дерева не теряет доказательство вложений.
            LegacyClassTalents.spent(player.classId, player.talents);
        }
        player.changedAt = timestamp(row, "changed-at");
        player.respecAt = timestamp(row, "respec-at");
        player.abilityAt = timestamp(row, "ability-at");
        player.ability2At = timestamp(row, "ability2-at");
        if (row.containsKey("development")) {
            if (!(row.get("development") instanceof Map<?, ?> development)) {
                throw new IllegalArgumentException("Некорректный собственный прогресс класса");
            }
            checkDevelopmentKeys(development);
            player.development = GSON.fromJson(GSON.toJson(development), ClassDevelopment.class);
        }
        return player;
    }

    private static void checkDevelopmentKeys(Map<?, ?> development) {
        checkKeys(development, Set.of("version", "xp", "xpSources", "trialAwards", "migration",
                "firstBuildAvailable", "revision"));
        for (String key : Set.of("version", "xp", "xpSources", "trialAwards", "firstBuildAvailable",
                "revision")) {
            if (!development.containsKey(key) || development.get(key) == null) {
                throw new IllegalArgumentException("Отсутствует поле прогресса: " + key);
            }
        }
        for (String key : Set.of("version", "xp", "revision")) {
            requiredNumber(development.get(key));
        }
        if (!(development.get("firstBuildAvailable") instanceof Boolean)
                || !(development.get("xpSources") instanceof Map<?, ?> sources)
                || !(development.get("trialAwards") instanceof List<?> awards)) {
            throw new IllegalArgumentException("Некорректные типы полей прогресса");
        }
        for (Object value : sources.values()) {
            requiredNumber(value);
        }
        if (new LinkedHashSet<>(awards).size() != awards.size()
                || awards.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("Повторные или некорректные испытания");
        }
        if (development.get("migration") instanceof Map<?, ?> migration) {
            checkKeys(migration, Set.of("classId", "oldLevel", "earnedPoints", "talents", "capturedAt"));
            for (String key : Set.of("oldLevel", "earnedPoints", "capturedAt")) {
                requiredNumber(migration.get(key));
            }
            if (!(migration.get("classId") instanceof String)
                    || !(migration.get("talents") instanceof List<?> talents)
                    || new LinkedHashSet<>(talents).size() != talents.size()
                    || talents.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException("Некорректный отчёт переноса");
            }
        } else if (development.get("migration") != null) {
            throw new IllegalArgumentException("Некорректный отчёт переноса");
        }
    }

    private static void checkKeys(Map<?, ?> row, Set<String> allowed) {
        for (Object key : row.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Неизвестное поле classes.json: " + key);
            }
        }
    }

    private static String text(Map<?, ?> row, String key, String fallback) {
        if (!row.containsKey(key)) {
            return fallback;
        }
        if (!(row.get(key) instanceof String result)) {
            throw new IllegalArgumentException("Ожидалась строка: " + key);
        }
        return result;
    }

    private static long timestamp(Map<?, ?> row, String key) {
        if (!row.containsKey(key)) {
            return 0;
        }
        long result = requiredNumber(row.get(key));
        if (result < 0 || result > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("Некорректное время: " + key);
        }
        return result;
    }

    private static long requiredNumber(Object value) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException("Ожидалось целое JSON-число");
        }
        return number.longValueExact();
    }

    private static long safeTimestamp(long value) {
        if (value > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("Время за пределами безопасного диапазона");
        }
        return Math.max(0L, value);
    }

    private void requireWritable() {
        if (!writable) {
            throw new IllegalStateException("ClassStore заблокирован после ошибки загрузки");
        }
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Данные игрока; создаются при первом обращении. */
    public synchronized Player of(UUID id) {
        requireWritable();
        if (id == null) {
            throw new IllegalArgumentException("id игрока не может быть null");
        }
        Player player = players.get(id);
        if (player == null) {
            if (players.size() >= MAX_PLAYERS) {
                throw new IllegalStateException("Достигнут предел игроков ClassStore");
            }
            player = new Player(id, "");
            players.put(id, player);
        }
        return player;
    }

    /** Запоминает актуальный ник: по нему удобно искать в админских командах. */
    public synchronized void rename(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) {
            return;
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("Слишком длинный ник игрока");
        }
        Player player = of(id);
        if (!name.equals(player.name)) {
            player.name = name;
            dirty = true;
        }
    }

    public synchronized String name(UUID id) {
        return of(id).name;
    }

    /** Выбранный класс или {@code ""}. */
    public synchronized String classOf(UUID id) {
        return of(id).classId;
    }

    public synchronized boolean hasClass(UUID id) {
        return !classOf(id).isEmpty();
    }

    /** Когда класс был выбран последний раз (0 — никогда). */
    public synchronized long changedAt(UUID id) {
        return of(id).changedAt;
    }

    public synchronized long respecAt(UUID id) {
        return of(id).respecAt;
    }

    /**
     * Когда игрок последний раз применял умение класса (0 — никогда).
     *
     * <p>Кулдаун хранится в файле, а не в памяти: иначе выход и повторный вход
     * сбрасывали бы его, и умение становилось бы бесконечным. Пересборка
     * талантов и админский сброс его не трогают — умение принадлежит классу, а
     * не дереву.</p>
     */
    public synchronized long abilityAt(UUID id) {
        return of(id).abilityAt;
    }

    /** Запоминает момент применения умения. */
    public synchronized void setAbilityAt(UUID id, long at) {
        Player player = of(id);
        long value = safeTimestamp(at);
        if (player.abilityAt != value) {
            player.abilityAt = value;
            dirty = true;
        }
    }

    /**
     * Когда игрок применял второе умение (1.4.175); 0 — никогда.
     *
     * <p>У умений свои откаты, поэтому и моменты разные: иначе после «Рывка»
     * нельзя было бы сразу ударить «Кличем». Оба поля живут в файле по той же
     * причине, что и первое.</p>
     */
    public synchronized long ability2At(UUID id) {
        return of(id).ability2At;
    }

    /** Запоминает момент применения второго умения. */
    public synchronized void setAbility2At(UUID id, long at) {
        Player player = of(id);
        long value = safeTimestamp(at);
        if (player.ability2At != value) {
            player.ability2At = value;
            dirty = true;
        }
    }

    /**
     * Записывает выбор класса.
     *
     * <p>Смена класса стирает таланты: они принадлежат прежнему дереву и без
     * этого стали бы «висеть» без класса. Это делает вызывающий сервис — здесь
     * только факт записи, чтобы правило сброса жило в одном месте.</p>
     *
     * @return {@code false}, если выбор не изменился
     */
    public synchronized boolean setClass(UUID id, String classId, long now) {
        if (!ClassCatalog.exists(classId)) {
            return false;
        }
        Player player = of(id);
        String value = classId.trim().toLowerCase(Locale.ROOT);
        if (player.classId.equals(value)) {
            return false;
        }
        long at = safeTimestamp(now);
        player.classId = value;
        player.talents.clear();
        player.changedAt = at;
        dirty = true;
        return true;
    }

    /** Забывает класс и таланты (админский сброс). */
    public synchronized boolean clearClass(UUID id) {
        Player player = of(id);
        if (player.classId.isEmpty() && player.talents.isEmpty() && player.changedAt == 0) {
            return false;
        }
        player.classId = "";
        player.talents.clear();
        player.changedAt = 0L;
        dirty = true;
        return true;
    }

    /** Изученные таланты в порядке изучения. */
    public synchronized Set<String> talents(UUID id) {
        return Set.copyOf(of(id).talents);
    }

    public synchronized int talentCount(UUID id) {
        return of(id).talents.size();
    }

    public synchronized boolean hasTalent(UUID id, String talentId) {
        return talentId != null && of(id).talents.contains(talentId.trim().toLowerCase(Locale.ROOT));
    }

    /** Добавляет талант; дубликат и мусор игнорируются. */
    public synchronized boolean learnTalent(UUID id, String talentId) {
        String key = talentId == null ? "" : talentId.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || ClassCatalog.talent(key) == null
                || of(id).talents.size() >= ClassCatalog.TALENTS_PER_CLASS) {
            return false;
        }
        Player player = of(id);
        if (!ClassCatalog.talent(key).classId().equals(player.classId)) {
            return false;
        }
        if (player.talents.add(key)) {
            dirty = true;
            return true;
        }
        return false;
    }

    /** Забывает талант (админская правка одиночного узла). */
    public synchronized boolean forgetTalent(UUID id, String talentId) {
        String key = talentId == null ? "" : talentId.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return false;
        }
        boolean removed = of(id).talents.remove(key);
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Стирает все таланты и запоминает момент пересборки. */
    public synchronized int clearTalents(UUID id, long now) {
        Player player = of(id);
        int count = player.talents.size();
        long at = safeTimestamp(now);
        player.talents.clear();
        player.respecAt = at;
        dirty = true;
        return count;
    }

    /** Полный сброс: класс, таланты и оба момента. */
    public synchronized boolean reset(UUID id) {
        requireWritable();
        Player player = players.get(id);
        if (player == null) {
            return false;
        }
        player.classId = "";
        player.talents.clear();
        player.changedAt = 0L;
        player.respecAt = 0L;
        dirty = true;
        return true;
    }

    public synchronized int size() {
        return players.size();
    }

    /** Ищет игрока по сохранённому нику (без учёта регистра). */
    public synchronized UUID byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Player player : players.values()) {
            if (name.equalsIgnoreCase(player.name)) {
                return player.id;
            }
        }
        return null;
    }

    /** Сколько игроков выбрали этот класс — для сводки админа. */
    public synchronized int countOf(String classId) {
        if (!ClassCatalog.exists(classId)) {
            return 0;
        }
        String value = classId.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (Player player : players.values()) {
            if (value.equals(player.classId)) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* ------------------------- собственный прогресс --------------------------- */

    synchronized ClassDevelopment development(UUID id) {
        return of(id).development;
    }

    /** Вызывается мигратором только после получения достоверной старой основы. */
    synchronized boolean migrateDevelopment(UUID id, int oldLevel, int oldEarned, long now) {
        Player player = of(id);
        if (player.development != null) {
            return false;
        }
        LegacyClassMigration report = new LegacyClassMigration(player.classId, oldLevel,
                oldEarned, player.talents, now);
        player.development = ClassDevelopment.migrate(report);
        player.talents.clear();
        // Откаты и сам класс не трогаются; первая сборка отмечена внутри прогресса.
        dirty = true;
        return true;
    }

    synchronized void startDevelopment(UUID id) {
        Player player = of(id);
        if (player.development == null) {
            if (!player.classId.isEmpty() || !player.talents.isEmpty()) {
                throw new IllegalStateException("Существующий выбор требует переноса, а не нового старта");
            }
            player.development = ClassDevelopment.fresh();
            dirty = true;
        }
    }

    synchronized long awardClassXp(UUID id, ClassDevelopment.Source source, long amount) {
        Player player = of(id);
        ClassDevelopment old = requireDevelopment(player);
        ClassDevelopment updated = old.awardXp(source, amount);
        if (old != updated) {
            player.development = updated;
            dirty = true;
        }
        return updated.xp() - old.xp();
    }

    synchronized boolean awardClassTrial(UUID id, String trialId, long xpReward) {
        Player player = of(id);
        ClassDevelopment old = requireDevelopment(player);
        ClassDevelopment updated = old.completeTrial(trialId, xpReward);
        if (old == updated) {
            return false;
        }
        player.development = updated;
        dirty = true;
        return true;
    }

    synchronized boolean consumeFirstBuild(UUID id) {
        Player player = of(id);
        ClassDevelopment old = requireDevelopment(player);
        ClassDevelopment updated = old.consumeFirstBuild();
        if (old == updated) {
            return false;
        }
        player.development = updated;
        dirty = true;
        return true;
    }

    private static ClassDevelopment requireDevelopment(Player player) {
        if (player.development == null) {
            throw new IllegalStateException("Собственный прогресс класса ещё не создан");
        }
        return player.development;
    }

    /* -------------------------------- запись ---------------------------------- */

    /** Чистый снимок: сам по себе не подтверждает успешную запись на диск. */
    public synchronized String snapshotJson() {
        requireWritable();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Player player : players.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.name);
            if (!player.classId.isEmpty()) {
                row.put("class", player.classId);
            }
            if (!player.talents.isEmpty()) {
                row.put("talents", new ArrayList<>(player.talents));
            }
            if (player.changedAt > 0) {
                row.put("changed-at", player.changedAt);
            }
            if (player.respecAt > 0) {
                row.put("respec-at", player.respecAt);
            }
            if (player.abilityAt > 0) {
                row.put("ability-at", player.abilityAt);
            }
            if (player.ability2At > 0) {
                row.put("ability2-at", player.ability2At);
            }
            if (player.development != null) {
                row.put("development", player.development);
            }
            rows.put(player.id.toString(), row);
        }
        data.put("players", rows);
        return GSON.toJson(data);
    }

    /** Снимки и их записи сериализованы: старое автосохранение не затирает финальное. */
    public void saveNow() {
        synchronized (writeLock) {
            String json;
            synchronized (this) {
                if (!writable || !dirty) {
                    return;
                }
                json = snapshotJson();
                dirty = false;
            }
            write(json);
        }
    }

    /** Заблокированное хранилище не пишется даже из onDisable после ошибки запуска. */
    public void flush() {
        synchronized (writeLock) {
            String json;
            synchronized (this) {
                if (!writable) {
                    return;
                }
                json = snapshotJson();
                dirty = false;
            }
            write(json);
        }
    }

    private void write(String json) {
        boolean success = false;
        try {
            backupLegacy();
            success = SafeJsonWriter.write(file, json, logger, "[CHRDK_Classes]");
        } catch (IOException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Classes] Запись отложена: " + error.getMessage());
            }
        }
        if (!success) {
            synchronized (this) {
                dirty = true;
            }
        }
    }

    /** До первой записи схемы 3 сохраняем точные байты старого файла без перезаписи копии. */
    private void backupLegacy() throws IOException {
        if (legacyBytes == null) {
            return;
        }
        if (!Arrays.equals(legacyBytes, Files.readAllBytes(file))) {
            throw new IOException("Исходный classes.json изменён после загрузки; перенос остановлен");
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacyBytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
        Path backup = file.resolveSibling("classes.json.before-v3-" + digest + ".bak");
        try {
            Files.write(backup, legacyBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException exists) {
            if (Files.size(backup) != legacyBytes.length
                    || !Arrays.equals(legacyBytes, Files.readAllBytes(backup))) {
                throw new IOException("Существующая резервная копия повреждена: " + backup, exists);
            }
        }
        legacyBytes = null;
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String id(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equalsIgnoreCase(text) ? parsed : null;
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }
}
