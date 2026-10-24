package ru.cherdakmd.chrdkclasses;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Классы игроков в {@code classes.json} (1.4.164).
 *
 * <p>Хранится только выбор: сам класс, изученные таланты и моменты смены
 * класса и пересборки. Уровень класса и число очков не сохраняются — они
 * выводятся из навыков, поэтому «файл говорит 12, а навыки дают 8» невозможно.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class ClassStore {

    /** Версия формата файла. */
    public static final int VERSION = 1;
    /** Потолок числа игроков в файле: защита от мусора. */
    public static final int MAX_PLAYERS = 200_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    /** Данные одного игрока. Мутировать можно только через методы хранилища. */
    public static final class Player {
        private final UUID id;
        private String name;
        private String classId = "";
        private final Set<String> talents = new LinkedHashSet<>();
        private long changedAt;
        private long respecAt;

        Player(UUID id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private boolean dirty;

    public ClassStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("classes.json");
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
            if (data.get("players") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row)
                            || players.size() >= MAX_PLAYERS) {
                        skipped++;
                        continue;
                    }
                    players.put(id, readPlayer(id, row));
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Classes] Пропущено записей classes.json: " + skipped + ".");
            }
        } catch (IOException | RuntimeException error) {
            if (logger != null) {
                logger.severe("[CHRDK_Classes] Не удалось прочитать " + file + ": "
                        + error.getMessage() + " — начну с чистого листа.");
            }
        }
    }

    private static Player readPlayer(UUID id, Map<?, ?> row) {
        String name = row.get("name") == null ? "" : row.get("name").toString();
        Player player = new Player(id, name);
        String classId = id(row.get("class"));
        // Неизвестный класс (аддон обновили, класс убрали) — считаем, что выбора нет.
        player.classId = ClassCatalog.exists(classId) ? classId : "";
        if (row.get("talents") instanceof List<?> talents) {
            for (Object element : talents) {
                String talent = id(element);
                if (ClassCatalog.talent(talent) == null
                        || !ClassCatalog.talent(talent).classId().equals(player.classId)) {
                    continue;
                }
                player.talents.add(talent);
            }
        }
        player.changedAt = Math.max(0L, number(row.get("changed-at")));
        player.respecAt = Math.max(0L, number(row.get("respec-at")));
        return player;
    }

    /* --------------------------------- доступ --------------------------------- */

    /** Данные игрока; создаются при первом обращении. */
    public synchronized Player of(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id игрока не может быть null");
        }
        Player player = players.get(id);
        if (player == null) {
            if (players.size() >= MAX_PLAYERS) {
                return new Player(id, "");
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
        player.classId = value;
        player.talents.clear();
        player.changedAt = Math.max(0L, now);
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
        player.talents.clear();
        player.respecAt = Math.max(0L, now);
        dirty = true;
        return count;
    }

    /** Полный сброс: класс, таланты и оба момента. */
    public synchronized boolean reset(UUID id) {
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

    /* -------------------------------- запись ---------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
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
            rows.put(player.id.toString(), row);
        }
        data.put("players", rows);
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
                logger.severe("[CHRDK_Classes] Не удалось записать " + file + ": "
                        + error.getMessage());
            }
        }
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String id(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    private static long number(Object raw) {
        return raw instanceof Number value ? value.longValue() : 0L;
    }
}
