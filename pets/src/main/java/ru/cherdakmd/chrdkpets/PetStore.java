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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        @Override
        public boolean equals(Object other) {
            return other instanceof PetData data && id.equals(data.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Comparator<PetData> NEWEST_FIRST =
            Comparator.comparingLong(PetData::created).reversed();
    private static final Comparator<PetData> TOP_ORDER =
            Comparator.comparingLong(PetData::xp).reversed()
                    .thenComparingLong(PetData::created);

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

    /** Читает файл; битые строки пропускаются с предупреждением. */
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
                pets.put(id, new PetData(
                        id,
                        ownerId,
                        string(row.get("owner-name")),
                        species,
                        string(row.get("name")),
                        number(row.get("xp")),
                        bool(row.get("dead")),
                        number(row.get("created")),
                        number(row.get("died"))));
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
        out.sort(TOP_ORDER);
        int size = Math.max(0, Math.min(limit, out.size()));
        return List.copyOf(out.subList(0, size));
    }

    /** Начисляет опыт живому питомцу; отрицательные значения игнорируются. */
    public synchronized void addXp(UUID id, long amount) {
        PetData data = get(id);
        if (data == null || data.dead || amount <= 0L) {
            return;
        }
        data.xp = data.xp > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data.xp + amount;
        dirty = true;
    }

    /** Устанавливает опыт напрямую (админская команда, тесты). */
    public synchronized void setXp(UUID id, long xp) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        long next = Math.max(0L, xp);
        if (next == data.xp) {
            return;
        }
        data.xp = next;
        dirty = true;
    }

    public synchronized void rename(UUID id, String name) {
        PetData data = get(id);
        if (data == null) {
            return;
        }
        String next = name == null ? "" : name.trim();
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
        String clean = name.trim();
        int changed = 0;
        for (PetData data : pets.values()) {
            if (ownerId.equals(data.ownerId) && !clean.equals(data.ownerName)) {
                data.ownerName = clean;
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

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
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
            rows.put(pet.id.toString(), fields);
        }
        data.put("pets", rows);
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

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }
}
