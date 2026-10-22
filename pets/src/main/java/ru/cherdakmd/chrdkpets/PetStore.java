package ru.cherdakmd.chrdkpets;

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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Данные питомцев ({@code pets.json}).
 *
 * <p>Ключ — UUID сущности, поэтому один игрок может держать сколько угодно
 * кошек и волков, а прогресс не путается между ними. В записи лежат владелец,
 * вид, кличка, опыт и отметка о смерти; уровень никогда не хранится — он
 * выводится из опыта через {@link PetCurve}. Смена кривой в конфиге не требует
 * миграции.</p>
 *
 * <p>С версии 1.4.144 запись хранит ещё и заботу: сытость, настроение, режим,
 * счётчики (убийства, рыбалка, минуты рядом, смерти), выученные трюки,
 * достижения, косметику (ошейник, свечение), отметки кулдаунов и дневник.
 * Старый файл без этих полей читается без миграции: отсутствующие значения
 * берутся из дефолтов, а версия файла просто становится 2.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}; синхронизация
 * на хранилище, потому что запись идёт с другого потока. Класс чистый
 * (Gson + UUID + NIO) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class PetStore {

    /** Запись одного питомца. Мутируется только методами хранилища. */
    public static final class PetData {
        private final UUID id;
        private UUID ownerId;
        private String ownerName;
        private final PetSpecies species;
        private String name;
        private long xp;
        private boolean dead;
        private final long created;
        private long diedAt;

        /* 1.4.144: забота, прогресс и косметика. */
        private int satiety = PetCare.Settings.DEFAULT.startSatiety();
        private int mood = PetCare.Settings.DEFAULT.moodNeutral();
        private long careAt;
        private PetMode mode = PetMode.FOLLOW;
        private int kills;
        private int fish;
        private int deaths;
        private int bestLevel = 1;
        private long togetherSeconds;
        private String collar = "";
        private boolean glow;
        private long lastFed;
        private long lastPetted;
        private long lastTrick;
        private long lastCalled;
        private long lastHealed;
        private long lastRenamed;
        private final Set<String> tricks = new LinkedHashSet<>();
        private final Set<String> achievements = new LinkedHashSet<>();
        private List<String> diary = new ArrayList<>();

        private PetData(UUID id, UUID ownerId, String ownerName, PetSpecies species,
                        String name, long xp, boolean dead, long created, long diedAt) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.species = species;
            this.name = name == null ? "" : name;
            this.xp = Math.max(0L, xp);
            this.dead = dead;
            this.created = created;
            this.diedAt = diedAt;
        }

        public UUID id() {
            return id;
        }

        public UUID ownerId() {
            return ownerId;
        }

        public String ownerName() {
            return ownerName;
        }

        public PetSpecies species() {
            return species;
        }

        /** Кличка; пустая строка — игрок ещё не называл питомца. */
        public String name() {
            return name;
        }

        public long xp() {
            return xp;
        }

        public boolean dead() {
            return dead;
        }

        public long created() {
            return created;
        }

        public long diedAt() {
            return diedAt;
        }

        public int satiety() {
            return satiety;
        }

        public int mood() {
            return mood;
        }

        public long careAt() {
            return careAt;
        }

        public PetMode mode() {
            return mode;
        }

        public int kills() {
            return kills;
        }

        public int fish() {
            return fish;
        }

        public int deaths() {
            return deaths;
        }

        /** Лучший достигнутый уровень; переживает падение уровня админом. */
        public int bestLevel() {
            return bestLevel;
        }

        public long togetherSeconds() {
            return togetherSeconds;
        }

        public long minutesTogether() {
            return togetherSeconds / 60L;
        }

        /** Выученные трюки (копия: снаружи набор не меняется). */
        public Set<String> tricks() {
            return Set.copyOf(tricks);
        }

        public boolean hasTrick(String trick) {
            return trick != null && tricks.contains(trick);
        }

        public Set<String> achievements() {
            return Set.copyOf(achievements);
        }

        public boolean hasAchievement(String achievement) {
            return achievement != null && achievements.contains(achievement);
        }

        /** Идентификатор ошейника; пустая строка — ванильный цвет. */
        public String collar() {
            return collar;
        }

        public boolean glow() {
            return glow;
        }

        public long lastFed() {
            return lastFed;
        }

        public long lastPetted() {
            return lastPetted;
        }

        public long lastTrick() {
            return lastTrick;
        }

        public long lastCalled() {
            return lastCalled;
        }

        public long lastHealed() {
            return lastHealed;
        }

        public long lastRenamed() {
            return lastRenamed;
        }

        /** Лента событий в формате {@link PetDiary}. */
        public List<String> diary() {
            return List.copyOf(diary);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Comparator<PetData> NEWEST_FIRST =
            Comparator.comparingLong(PetData::created).reversed();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, PetData> pets = new LinkedHashMap<>();
    private boolean dirty;

    public PetStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("pets.json");
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
            if (data == null || !(data.get("pets") instanceof Map<?, ?> rows)) {
                return;
            }
            int skipped = 0;
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                UUID id = parseUuid(entry.getKey());
                if (id == null || !(entry.getValue() instanceof Map<?, ?> row)) {
                    skipped++;
                    continue;
                }
                UUID ownerId = parseUuid(row.get("owner"));
                PetSpecies species = PetSpecies.byId(string(row.get("species")));
                if (ownerId == null || species == null) {
                    skipped++;
                    continue;
                }
                PetData pet = new PetData(
                        id,
                        ownerId,
                        string(row.get("owner-name")),
                        species,
                        string(row.get("name")),
                        number(row.get("xp")),
                        bool(row.get("dead")),
                        number(row.get("created")),
                        number(row.get("died")));
                readExtras(pet, row);
                pets.put(id, pet);
            }
            if (skipped > 0) {
                logger.warning("[CHRDK_Pets] Пропущено записей pets.json: " + skipped + ".");
            }
            logger.info("[CHRDK_Pets] Загружено питомцев: " + pets.size()
                    + " (живых " + aliveCount() + ").");
        } catch (Exception exception) {
            logger.severe("[CHRDK_Pets] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
        }
    }

    /** Дочитывает поля версии 2; всё отсутствующее остаётся дефолтным. */
    private static void readExtras(PetData pet, Map<?, ?> row) {
        pet.satiety = clampInt(intNumber(row.get("satiety")),
                PetCare.Settings.DEFAULT.startSatiety());
        pet.mood = clampInt(intNumber(row.get("mood")), PetCare.Settings.DEFAULT.moodNeutral());
        pet.careAt = number(row.get("care-at"));
        pet.mode = PetMode.byId(string(row.get("mode")));
        pet.kills = (int) Math.min(Integer.MAX_VALUE, number(row.get("kills")));
        pet.fish = (int) Math.min(Integer.MAX_VALUE, number(row.get("fish")));
        pet.deaths = (int) Math.min(Integer.MAX_VALUE, number(row.get("deaths")));
        pet.bestLevel = Math.max(1, (int) Math.min(Integer.MAX_VALUE, number(row.get("best-level"))));
        pet.togetherSeconds = number(row.get("together-seconds"));
        pet.collar = string(row.get("collar"));
        pet.glow = bool(row.get("glow"));
        pet.lastFed = number(row.get("last-fed"));
        pet.lastPetted = number(row.get("last-petted"));
        pet.lastTrick = number(row.get("last-trick"));
        pet.lastCalled = number(row.get("last-called"));
        pet.lastHealed = number(row.get("last-healed"));
        pet.lastRenamed = number(row.get("last-renamed"));
        pet.tricks.addAll(strings(row.get("tricks")));
        pet.achievements.addAll(strings(row.get("achievements")));
        List<String> diary = strings(row.get("diary"));
        pet.diary = new ArrayList<>(diary);
    }

    /** Регистрирует нового питомца. Повторный вызов с тем же UUID возвращает старую запись. */
    public synchronized PetData create(UUID id, UUID ownerId, String ownerName,
                                       PetSpecies species, String name, long now) {
        if (id == null || ownerId == null || species == null) {
            return null;
        }
        PetData existing = pets.get(id);
        if (existing != null) {
            return existing;
        }
        long created = now <= 0L ? System.currentTimeMillis() : now;
        PetData data = new PetData(id, ownerId, ownerName, species, name, 0L, false,
                created, 0L);
        data.careAt = created;
        pets.put(id, data);
        dirty = true;
        return data;
    }

    public synchronized PetData get(UUID id) {
        return id == null ? null : pets.get(id);
    }

    public synchronized boolean has(UUID id) {
        return id != null && pets.containsKey(id);
    }

    /** Все записи в порядке появления (диагностика, админские команды). */
    public synchronized List<PetData> all() {
        return List.copyOf(pets.values());
    }

    public synchronized List<PetData> petsOf(UUID ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        List<PetData> out = new ArrayList<>();
        for (PetData data : pets.values()) {
            if (ownerId.equals(data.ownerId)) {
                out.add(data);
            }
        }
        out.sort(NEWEST_FIRST);
        return List.copyOf(out);
    }

    public synchronized List<PetData> alivePetsOf(UUID ownerId) {
        List<PetData> out = new ArrayList<>();
        for (PetData data : petsOf(ownerId)) {
            if (!data.dead) {
                out.add(data);
            }
        }
        return List.copyOf(out);
    }

    /** Топ живых питомцев по опыту; при равенстве — по времени появления. */
    public synchronized List<PetData> top(int limit) {
        return top(limit, null);
    }

    /** Топ живых питомцев одного вида ({@code null} — все виды). */
    public synchronized List<PetData> top(int limit, PetSpecies species) {
        List<PetData> out = new ArrayList<>();
        for (PetData data : pets.values()) {
            if (data.dead || (species != null && data.species != species)) {
                continue;
            }
            out.add(data);
        }
        out.sort(Comparator.comparingLong(PetData::xp).reversed()
                .thenComparing(PetData::created));
        int size = Math.max(0, Math.min(limit, out.size()));
        return List.copyOf(out.subList(0, size));
    }

    public synchronized void addXp(UUID id, long amount) {
        PetData data = get(id);
        if (data == null || data.dead || amount <= 0L) {
            return;
        }
        long next = data.xp + amount;
        if (next < 0L) {
            next = Long.MAX_VALUE;
        }
        data.xp = next;
        dirty = true;
    }

    public synchronized void setXp(UUID id, long xp) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        data.xp = Math.max(0L, xp);
        dirty = true;
    }

    public synchronized void rename(UUID id, String name) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        String next = name == null ? "" : name;
        if (next.equals(data.name)) {
            return;
        }
        data.name = next;
        dirty = true;
    }

    public synchronized void setOwner(UUID id, UUID ownerId, String ownerName) {
        PetData data = get(id);
        if (data == null || ownerId == null || ownerId.equals(data.ownerId)) {
            return;
        }
        data.ownerId = ownerId;
        data.ownerName = ownerName == null ? "" : ownerName;
        dirty = true;
    }

    /** Обновляет последний известный ник владельца во всех его записях. */
    public synchronized int touchOwnerName(UUID ownerId, String name) {
        if (ownerId == null || name == null || name.isBlank()) {
            return 0;
        }
        int changed = 0;
        for (PetData data : pets.values()) {
            if (ownerId.equals(data.ownerId) && !name.equals(data.ownerName)) {
                data.ownerName = name;
                changed++;
            }
        }
        if (changed > 0) {
            dirty = true;
        }
        return changed;
    }

    public synchronized void markDead(UUID id, long when) {
        PetData data = get(id);
        if (data == null || data.dead) {
            return;
        }
        data.dead = true;
        data.diedAt = when <= 0L ? System.currentTimeMillis() : when;
        dirty = true;
    }

    /* ------------------------------------------------------------ забота и прогресс */

    /** Сохраняет шкалы заботы и время расчёта. */
    public synchronized void setCare(UUID id, int satiety, int mood, long at) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        int nextSatiety = clampInt(satiety, data.satiety);
        int nextMood = clampInt(mood, data.mood);
        long nextAt = at <= 0L ? data.careAt : at;
        if (nextSatiety == data.satiety && nextMood == data.mood && nextAt == data.careAt) {
            return;
        }
        data.satiety = nextSatiety;
        data.mood = nextMood;
        data.careAt = nextAt;
        dirty = true;
    }

    public synchronized void setMode(UUID id, PetMode mode) {
        PetData data = get(id);
        if (data == null || mode == null || mode == data.mode) {
            return;
        }
        data.mode = mode;
        dirty = true;
    }

    public synchronized void addKill(UUID id) {
        PetData data = get(id);
        if (data == null || data.dead) {
            return;
        }
        data.kills++;
        dirty = true;
    }

    public synchronized void addFish(UUID id) {
        PetData data = get(id);
        if (data == null || data.dead) {
            return;
        }
        data.fish++;
        dirty = true;
    }

    public synchronized void addDeath(UUID id) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        data.deaths++;
        dirty = true;
    }

    /** Поднимает планку лучшего уровня (вниз не опускается). */
    public synchronized void setBestLevel(UUID id, int level) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        int next = Math.max(data.bestLevel, Math.max(1, level));
        if (next == data.bestLevel) {
            return;
        }
        data.bestLevel = next;
        dirty = true;
    }

    public synchronized void addTogetherSeconds(UUID id, long seconds) {
        PetData data = get(id);
        if (data == null || data.dead || seconds <= 0L) {
            return;
        }
        data.togetherSeconds = Math.min(Long.MAX_VALUE, data.togetherSeconds + seconds);
        dirty = true;
    }

    /** Учит трюку; {@code true} — трюк новый. */
    public synchronized boolean learnTrick(UUID id, String trick) {
        PetData data = get(id);
        if (data == null || data.dead || trick == null || trick.isBlank()) {
            return false;
        }
        if (!data.tricks.add(trick.trim())) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Закрывает достижение; {@code true} — оно новое. */
    public synchronized boolean unlockAchievement(UUID id, String achievement) {
        PetData data = get(id);
        if (data == null || achievement == null || achievement.isBlank()) {
            return false;
        }
        if (!data.achievements.add(achievement.trim())) {
            return false;
        }
        dirty = true;
        return true;
    }

    public synchronized void setCollar(UUID id, String collar) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        String next = collar == null ? "" : collar.trim();
        if (next.equals(data.collar)) {
            return;
        }
        data.collar = next;
        dirty = true;
    }

    public synchronized void setGlow(UUID id, boolean glow) {
        PetData data = get(id);
        if (data == null || data.glow == glow) {
            return;
        }
        data.glow = glow;
        dirty = true;
    }

    /**
     * Отмечает кулдаун действия: {@code fed}, {@code petted}, {@code trick},
     * {@code called}, {@code healed}, {@code renamed}. Неизвестное имя игнорируется.
     */
    public synchronized void markAction(UUID id, String action, long when) {
        PetData data = get(id);
        if (data == null || action == null) {
            return;
        }
        long stamp = when <= 0L ? System.currentTimeMillis() : when;
        switch (action) {
            case "fed" -> data.lastFed = stamp;
            case "petted" -> data.lastPetted = stamp;
            case "trick" -> data.lastTrick = stamp;
            case "called" -> data.lastCalled = stamp;
            case "healed" -> data.lastHealed = stamp;
            case "renamed" -> data.lastRenamed = stamp;
            default -> {
                return;
            }
        }
        dirty = true;
    }

    /** Добавляет событие в дневник, обрезая ленту до {@code limit}. */
    public synchronized void addDiary(UUID id, long at, String code, String arg, int limit) {
        PetData data = get(id);
        if (data == null || code == null || code.isBlank()) {
            return;
        }
        data.diary = PetDiary.append(data.diary,
                new PetDiary.Entry(at <= 0L ? System.currentTimeMillis() : at, code, arg), limit);
        dirty = true;
    }

    /**
     * Удаляет память о погибших питомцах старше {@code keepDays} дней.
     * {@code keepDays = 0} — не хранить память вовсе.
     *
     * @return сколько записей удалено
     */
    public synchronized int purgeDead(int keepDays, long now) {
        long cutoff = now - Math.max(0, keepDays) * 86_400_000L;
        int removed = 0;
        var iterator = pets.entrySet().iterator();
        while (iterator.hasNext()) {
            PetData data = iterator.next().getValue();
            if (!data.dead) {
                continue;
            }
            long died = data.diedAt > 0L ? data.diedAt : data.created;
            if (keepDays <= 0 || died < cutoff) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    public synchronized int size() {
        return pets.size();
    }

    public synchronized int aliveCount() {
        int count = 0;
        for (PetData data : pets.values()) {
            if (!data.dead) {
                count++;
            }
        }
        return count;
    }

    /** Суммарный опыт всех записей — для админской сводки. */
    public synchronized long totalXp() {
        long total = 0L;
        for (PetData data : pets.values()) {
            total += data.xp;
        }
        return total;
    }

    /** Сколько трюков выучено всеми питомцами — для админской сводки. */
    public synchronized int trickCount() {
        int total = 0;
        for (PetData data : pets.values()) {
            total += data.tricks.size();
        }
        return total;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 2);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (PetData pet : pets.values()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("owner", pet.ownerId.toString());
            fields.put("owner-name", pet.ownerName);
            fields.put("species", pet.species.id());
            fields.put("name", pet.name);
            fields.put("xp", pet.xp);
            fields.put("created", pet.created);
            fields.put("dead", pet.dead);
            if (pet.dead) {
                fields.put("died", pet.diedAt);
            }
            fields.put("satiety", pet.satiety);
            fields.put("mood", pet.mood);
            fields.put("care-at", pet.careAt);
            fields.put("mode", pet.mode.id());
            fields.put("kills", pet.kills);
            fields.put("fish", pet.fish);
            fields.put("deaths", pet.deaths);
            fields.put("best-level", pet.bestLevel);
            fields.put("together-seconds", pet.togetherSeconds);
            fields.put("tricks", List.copyOf(pet.tricks));
            fields.put("achievements", List.copyOf(pet.achievements));
            if (!pet.collar.isEmpty()) {
                fields.put("collar", pet.collar);
            }
            fields.put("glow", pet.glow);
            putStamp(fields, "last-fed", pet.lastFed);
            putStamp(fields, "last-petted", pet.lastPetted);
            putStamp(fields, "last-trick", pet.lastTrick);
            putStamp(fields, "last-called", pet.lastCalled);
            putStamp(fields, "last-healed", pet.lastHealed);
            putStamp(fields, "last-renamed", pet.lastRenamed);
            if (!pet.diary.isEmpty()) {
                fields.put("diary", List.copyOf(pet.diary));
            }
            rows.put(pet.id.toString(), fields);
        }
        data.put("pets", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    private static void putStamp(Map<String, Object> fields, String key, long value) {
        if (value > 0L) {
            fields.put(key, value);
        }
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
            logger.severe("[CHRDK_Pets] Не удалось записать " + file + ": "
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

    private static int clampInt(int value, int fallback) {
        return Math.max(0, Math.min(100, value < 0 ? fallback : value));
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static int intNumber(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : collection) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
