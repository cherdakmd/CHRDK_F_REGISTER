package ru.cherdakmd.chrdkclaims;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Данные приватов (claims-data.json): чанки по мирам (запись плоским списком),
 * казна игроков, отложенные вести (долги/утраты) и статистика.
 *
 * <p>Запись: снимок под блокировкой → JSON во временный файл → атомарная замена;
 * предыдущая версия сохраняется в {@code claims-data.json.bak}. Повреждённый файл
 * не перезаписывается, а откладывается рядом как {@code claims-data.json.corrupt-<millis>}.
 * Чтение и снимок синхронизированы, поэтому асинхронное сохранение не мешает игре.</p>
 */
public final class ClaimStore {

    /** Версия схемы данных (для будущих миграций). */
    public static final int SCHEMA_VERSION = 2;
    /** Сколько отложенных вестей хранить на игрока (старые вытесняются). */
    public static final int MAX_NOTICES = 40;

    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Path backup;
    private final Logger logger;
    /** world → (chunkKey → Claim). */
    private final Map<String, Map<Long, Claim>> claims = new HashMap<>();
    private final Map<String, Integer> treasury = new LinkedHashMap<>();
    private final Map<String, List<String>> notices = new LinkedHashMap<>();
    /** Персональные лимиты чанков, выданные администратором (ник → чанков). */
    private final Map<String, Integer> limits = new LinkedHashMap<>();
    private final AtomicBoolean saving = new AtomicBoolean();
    private final Object writeLock = new Object();

    private long chunksClaimedTotal;
    private long diamondsSpent;
    private long taxBurned;
    private long revision;
    private boolean dirty;
    /** Запись запрещена: повреждённый файл не удалось отложить, затирать его нельзя. */
    private volatile boolean blocked;

    public ClaimStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("claims-data.json");
        this.backup = dataFolder.resolve("claims-data.json.bak");
        this.logger = logger;
    }

    // ------------------------------------------------------------------ io --

    public synchronized void load() {
        reset();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> data;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data = GSON.fromJson(reader, DATA_TYPE);
        } catch (IOException | RuntimeException exception) {
            quarantine(exception);
            return;
        }
        if (data == null) {
            return;
        }
        try {
            read(data);
            dirty = false;
        } catch (RuntimeException exception) {
            reset();
            quarantine(exception);
        }
    }

    /** Пишет данные синхронно. Вызывается из onDisable и асинхронной задачи. */
    public void save() {
        if (blocked) {
            return;
        }
        Snapshot snapshot = snapshot();
        boolean written;
        synchronized (writeLock) {
            written = write(snapshot.data());
        }
        if (written) {
            synchronized (this) {
                if (revision == snapshot.revision()) {
                    dirty = false;
                }
            }
        }
    }

    /** Планирует асинхронную запись; параллельные запуски не дублируются. */
    public void saveAsync(org.bukkit.plugin.Plugin plugin) {
        if (!dirty || blocked || plugin == null) {
            return;
        }
        if (!saving.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    save();
                } finally {
                    saving.set(false);
                }
            });
        } catch (RuntimeException exception) {
            saving.set(false);
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось запланировать сохранение: "
                    + exception.getMessage(), exception);
        }
    }

    public synchronized void markDirty() {
        touch();
    }

    private void reset() {
        claims.clear();
        treasury.clear();
        notices.clear();
        limits.clear();
        chunksClaimedTotal = 0;
        diamondsSpent = 0;
        taxBurned = 0;
        revision = 0;
        dirty = false;
        blocked = false;
    }

    private void touch() {
        dirty = true;
        revision++;
    }

    @SuppressWarnings("unchecked")
    private void read(Map<String, Object> data) {
        int version = (int) longOf(data.get("version"));
        if (version > SCHEMA_VERSION) {
            logger.warning("[CHRDK_Claims] claims-data.json версии " + version
                    + " новее плагина (" + SCHEMA_VERSION + "): неизвестные поля будут проигнорированы.");
        }
        long now = System.currentTimeMillis();
        int repairedDates = 0;
        int duplicates = 0;
        Object rawClaims = data.get("claims");
        if (rawClaims instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                String world = str(map.get("w"));
                String owner = str(map.get("o"));
                if (world == null || world.isBlank() || owner == null || owner.isBlank()) {
                    continue;
                }
                int x = (int) longOf(map.get("x"));
                int z = (int) longOf(map.get("z"));
                long paidUntil = longOf(map.get("p"));
                if (paidUntil <= 0) {
                    paidUntil = now;
                    repairedDates++;
                }
                Claim claim = new Claim(world, x, z, owner, paidUntil);
                claim.debtDays = clamp((int) longOf(map.get("d")), 0, 100_000);
                claim.salePrice = clamp((int) longOf(map.get("s")), 0, 1_000_000);
                claim.greeting = Claim.normalizeGreeting(str(map.get("g")));
                Set<String> seenMembers = new LinkedHashSet<>();
                if (map.get("m") instanceof List<?> members) {
                    for (Object member : members) {
                        if (member == null) {
                            continue;
                        }
                        String name = String.valueOf(member).trim();
                        if (name.isEmpty() || claim.isOwner(name)
                                || !seenMembers.add(name.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        claim.members.add(name);
                    }
                }
                // Роли участников: только известные значения, только для участников.
                if (map.get("mr") instanceof Map<?, ?> roleMap) {
                    for (Map.Entry<?, ?> roleEntry : roleMap.entrySet()) {
                        if (roleEntry.getKey() == null || roleEntry.getValue() == null) {
                            continue;
                        }
                        String member = String.valueOf(roleEntry.getKey()).trim();
                        Claim.Role role = parseRole(String.valueOf(roleEntry.getValue()).trim());
                        if (role != null) {
                            claim.applyRole(member, role);
                        }
                    }
                }
                Claim previous = claims.computeIfAbsent(world, key -> new HashMap<>())
                        .putIfAbsent(claim.key(), claim);
                if (previous != null) {
                    duplicates++;
                }
            }
        }
        if (repairedDates > 0) {
            logger.warning("[CHRDK_Claims] В claims-data.json " + repairedDates
                    + " приват(ов) без корректной даты оплаты — рента начнёт начисляться с этого запуска.");
        }
        if (duplicates > 0) {
            logger.warning("[CHRDK_Claims] В claims-data.json найдено " + duplicates
                    + " дубликат(ов) чанков — оставлены первые записи.");
        }

        if (data.get("treasury") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String name = String.valueOf(entry.getKey());
                int balance = clamp((int) longOf(entry.getValue()), 0, Integer.MAX_VALUE);
                if (!name.isBlank() && balance > 0) {
                    treasury.put(name, balance);
                }
            }
        }
        if (data.get("notices") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getValue() instanceof List<?> list) || entry.getKey() == null) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (Object line : list) {
                    if (line != null) {
                        lines.add(String.valueOf(line));
                    }
                }
                trimNotices(lines);
                if (!lines.isEmpty()) {
                    notices.put(String.valueOf(entry.getKey()), lines);
                }
            }
        }
        if (data.get("limits") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String name = String.valueOf(entry.getKey()).trim();
                int limit = clamp((int) longOf(entry.getValue()), 1, 100_000);
                if (!name.isEmpty()) {
                    limits.put(name, limit);
                }
            }
        }
        if (data.get("stats") instanceof Map<?, ?> stats) {
            chunksClaimedTotal = Math.max(0, longOf(stats.get("chunksClaimedTotal")));
            diamondsSpent = Math.max(0, longOf(stats.get("diamondsSpent")));
            taxBurned = Math.max(0, longOf(stats.get("taxBurned")));
        }
    }

    private Snapshot snapshot() {
        synchronized (this) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", SCHEMA_VERSION);
            List<Map<String, Object>> claimsList = new ArrayList<>();
            for (Map<Long, Claim> world : claims.values()) {
                for (Claim claim : world.values()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("w", claim.world);
                    entry.put("x", claim.x);
                    entry.put("z", claim.z);
                    entry.put("o", claim.owner);
                    entry.put("p", claim.paidUntil);
                    entry.put("d", claim.debtDays);
                    entry.put("s", claim.salePrice);
                    if (claim.hasGreeting()) {
                        entry.put("g", claim.greeting);
                    }
                    if (!claim.members.isEmpty()) {
                        entry.put("m", new ArrayList<>(claim.members));
                        Map<String, Claim.Role> explicit = claim.explicitRoles();
                        if (!explicit.isEmpty()) {
                            Map<String, String> saved = new LinkedHashMap<>();
                            explicit.forEach((name, role) -> saved.put(name, role.name()));
                            entry.put("mr", saved);
                        }
                    }
                    claimsList.add(entry);
                }
            }
            data.put("claims", claimsList);
            data.put("treasury", new LinkedHashMap<>(treasury));
            if (!limits.isEmpty()) {
                data.put("limits", new LinkedHashMap<>(limits));
            }
            if (!notices.isEmpty()) {
                Map<String, List<String>> copy = new LinkedHashMap<>();
                notices.forEach((name, lines) -> copy.put(name, new ArrayList<>(lines)));
                data.put("notices", copy);
            }
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("chunksClaimedTotal", chunksClaimedTotal);
            stats.put("diamondsSpent", diamondsSpent);
            stats.put("taxBurned", taxBurned);
            data.put("stats", stats);
            return new Snapshot(data, revision);
        }
    }

    private boolean write(Map<String, Object> data) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException backupException) {
                // Нехватка места/прав на .bak не должна блокировать сохранение данных.
                logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось обновить "
                        + backup.getFileName() + ": " + backupException.getMessage(), backupException);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось записать claims-data.json: "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    /** Повреждённый файл не затирается: откладываем его рядом для ручного восстановления. */
    private void quarantine(Exception exception) {
        Path broken = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            logger.log(Level.SEVERE, "[CHRDK_Claims] claims-data.json не удалось прочитать ("
                    + exception.getMessage() + "). Файл сохранён как " + broken.getFileName()
                    + "; плагин стартует с пустыми приватами. Проверьте резервную копию "
                    + backup.getFileName() + ".", exception);
        } catch (IOException moveException) {
            blocked = true;
            logger.log(Level.SEVERE, "[CHRDK_Claims] claims-data.json повреждён, и его не удалось "
                    + "отложить в сторону. Запись новых данных остановлена, чтобы не затереть файл: "
                    + "освободите место/права и перезапустите сервер.", moveException);
        }
    }

    private record Snapshot(Map<String, Object> data, long revision) {
    }

    // ------------------------------------------------------------ backups --

    /** Файл резервной копии для списка администратора. */
    public record BackupEntry(String name, long size, long modified) {
    }

    /** Все копии рядом с данными: `.bak`, `.corrupt-*`, `.pre-restore-*` (свежие первыми). */
    public synchronized List<BackupEntry> backups() {
        Path parent = file.getParent();
        List<BackupEntry> entries = new ArrayList<>();
        if (parent == null || !Files.isDirectory(parent)) {
            return entries;
        }
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("claims-data.json"))
                    .forEach(path -> {
                        try {
                            entries.add(new BackupEntry(path.getFileName().toString(),
                                    Files.size(path), Files.getLastModifiedTime(path).toMillis()));
                        } catch (IOException exception) {
                            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось прочитать "
                                    + path.getFileName() + ": " + exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось перечислить резервные копии: "
                    + exception.getMessage(), exception);
            return entries;
        }
        entries.sort((left, right) -> Long.compare(right.modified(), left.modified()));
        return entries;
    }

    /** Итог восстановления: успех, файла нет, имя недопустимо или файл нечитаем. */
    public enum RestoreResult {
        OK, NOT_FOUND, INVALID, IO_ERROR
    }

    /**
     * Восстанавливает данные из копии рядом с {@code claims-data.json}.
     *
     * <p>Имя принимается только как имя файла в папке данных (без путей и «..»),
     * содержимое сначала проверяется разбором JSON, текущие данные сохраняются как
     * {@code claims-data.json.pre-restore-<millis>}. Так неудачное восстановление
     * не может уничтожить рабочий файл.</p>
     */
    public synchronized RestoreResult restoreFrom(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return RestoreResult.INVALID;
        }
        String name = fileName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.startsWith("claims-data.json")) {
            return RestoreResult.INVALID;
        }
        Path source = file.resolveSibling(name);
        if (source.equals(file) || !Files.isRegularFile(source)) {
            return RestoreResult.NOT_FOUND;
        }
        if (!isReadableData(source)) {
            return RestoreResult.INVALID;
        }
        try {
            // Тот же лок, что у save(): незавершённая запись не перезапишет восстановленное.
            synchronized (writeLock) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, file.resolveSibling(
                                    "claims-data.json.pre-restore-" + System.currentTimeMillis()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                load();
            }
            logger.info("[CHRDK_Claims] Данные восстановлены из " + name + ".");
            return RestoreResult.OK;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "[CHRDK_Claims] Не удалось восстановить данные из "
                    + name + ": " + exception.getMessage(), exception);
            return RestoreResult.IO_ERROR;
        }
    }

    /** Проверка, что файл — корректный JSON-объект данных (до подмены рабочего файла). */
    private boolean isReadableData(Path source) {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            return data != null;
        } catch (IOException | RuntimeException exception) {
            logger.warning("[CHRDK_Claims] Копия " + source.getFileName()
                    + " не читается как данные приватов: " + exception.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------- claims --

    public synchronized Claim claimAt(String world, int chunkX, int chunkZ) {
        Map<Long, Claim> inWorld = claims.get(world);
        return inWorld == null ? null : inWorld.get(ClaimMath.chunkKey(chunkX, chunkZ));
    }

    public synchronized void add(Claim claim) {
        Map<Long, Claim> world = claims.computeIfAbsent(claim.world, key -> new HashMap<>());
        Claim previous = world.put(claim.key(), claim);
        if (previous != null) {
            logger.warning("[CHRDK_Claims] Приват " + claim.where() + " перезаписан новым владельцем "
                    + claim.owner + " (был " + previous.owner + ").");
        }
        chunksClaimedTotal++;
        touch();
    }

    public synchronized void remove(Claim claim) {
        Map<Long, Claim> world = claims.get(claim.world);
        if (world != null) {
            if (!world.remove(claim.key(), claim)) {
                logger.warning("[CHRDK_Claims] Приват " + claim.where()
                        + " уже заменён другой записью — удаление пропущено.");
            }
            if (world.isEmpty()) {
                claims.remove(claim.world);
            }
        }
        touch();
    }

    /** Удаляет все приваты мира (например, после смены карты). @return сколько удалено. */
    public synchronized int removeWorld(String world) {
        Map<Long, Claim> removed = claims.remove(world);
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        touch();
        return removed.size();
    }

    public synchronized List<Claim> ofOwner(String owner) {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    result.add(claim);
                }
            }
        }
        return result;
    }

    public synchronized List<Claim> inWorld(String world) {
        Map<Long, Claim> inWorld = claims.get(world);
        return inWorld == null ? List.of() : new ArrayList<>(inWorld.values());
    }

    public synchronized List<Claim> all() {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            result.addAll(world.values());
        }
        return result;
    }

    public synchronized int count(String owner) {
        int total = 0;
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    total++;
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------ treasury --

    /**
     * Баланс казны. Ник сравнивается без учёта регистра: администратор может
     * передать «steve», пока в данных записан «Steve».
     */
    public synchronized int treasuryOf(String name) {
        if (name == null) {
            return 0;
        }
        Integer balance = treasury.get(keyIgnoreCase(treasury, name));
        return balance == null ? 0 : balance;
    }

    /** @return новый баланс (не больше {@link Integer#MAX_VALUE}). */
    public synchronized int addTreasury(String name, int amount) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        long sum = (long) treasuryOf(name) + Math.max(0, amount);
        int balance = (int) Math.min(Integer.MAX_VALUE, sum);
        String key = keyIgnoreCase(treasury, name);
        treasury.put(key == null ? name : key, balance);
        touch();
        return balance;
    }

    public synchronized boolean takeTreasury(String name, int amount) {
        if (amount <= 0) {
            return true;
        }
        String key = keyIgnoreCase(treasury, name);
        if (key == null) {
            return false;
        }
        int balance = treasury.get(key);
        if (balance < amount) {
            return false;
        }
        treasury.put(key, balance - amount);
        touch();
        return true;
    }

    public synchronized long totalTreasury() {
        long total = 0;
        for (int balance : treasury.values()) {
            total += balance;
        }
        return total;
    }

    // ------------------------------------------------------------- notices --

    public synchronized void addNotice(String name, String text) {
        if (name == null || name.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String existing = keyIgnoreCase(notices, name);
        List<String> lines = notices.computeIfAbsent(
                existing == null ? name : existing, key -> new ArrayList<>());
        lines.add(text);
        trimNotices(lines);
        touch();
    }

    private void trimNotices(List<String> lines) {
        while (lines.size() > MAX_NOTICES) {
            lines.remove(0);
        }
    }

    /** Забирает (и очищает) вести игрока. */
    public synchronized List<String> pollNotices(String name) {
        if (name == null) {
            return List.of();
        }
        List<String> lines = notices.remove(keyIgnoreCase(notices, name));
        if (lines == null) {
            return List.of();
        }
        touch();
        return List.copyOf(lines);
    }

    // -------------------------------------------------------------- limits --

    /** Персональный лимит администратора или {@code null}, если действует общий. */
    public synchronized Integer limitOverride(String name) {
        return name == null ? null : limits.get(keyIgnoreCase(limits, name));
    }

    /** Назначает персональный лимит (1..100000) — он главнее базы и бонуса ранга. */
    public synchronized void setLimitOverride(String name, int limit) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = keyIgnoreCase(limits, name);
        limits.put(key == null ? name : key, clamp(limit, 1, 100_000));
        touch();
    }

    /** Снимает персональный лимит. @return был ли он назначен */
    public synchronized boolean clearLimitOverride(String name) {
        String key = name == null ? null : keyIgnoreCase(limits, name);
        if (key == null) {
            return false;
        }
        limits.remove(key);
        touch();
        return true;
    }

    /** Все персональные лимиты (для админской статистики/диагностики). */
    public synchronized Map<String, Integer> limitOverrides() {
        return Map.copyOf(limits);
    }

    // ------------------------------------------------------------ transfer --

    /** Итог переноса владений: приваты, казна, отложенные вести, персональный лимит. */
    public record TransferSummary(int claims, int treasury, int notices, boolean limitMoved) {
    }

    /**
     * Переносит всё с одного ника на другой — сценарий смены ника игрока.
     *
     * <p>Переносятся приваты (владение), казна, отложенные вести и персональный
     * лимит; ники сравниваются без учёта регистра. Если получатель был участником
     * чужого привата, из списка участников он удаляется — он теперь владелец.</p>
     */
    public synchronized TransferSummary transferAll(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()
                || from.equalsIgnoreCase(to)) {
            return new TransferSummary(0, 0, 0, false);
        }
        int movedClaims = 0;
        for (Claim claim : all()) {
            if (!claim.isOwner(from)) {
                continue;
            }
            claim.owner = to;
            claim.members.removeIf(member -> member.equalsIgnoreCase(to));
            movedClaims++;
        }

        int movedTreasury = 0;
        String fromTreasury = keyIgnoreCase(treasury, from);
        if (fromTreasury != null) {
            movedTreasury = treasury.remove(fromTreasury);
        }
        if (movedTreasury > 0) {
            String toTreasury = keyIgnoreCase(treasury, to);
            String key = toTreasury == null ? to : toTreasury;
            int current = treasury.getOrDefault(key, 0);
            treasury.put(key, (int) Math.min(Integer.MAX_VALUE, (long) current + movedTreasury));
        }

        int movedNotices = 0;
        String fromNotices = keyIgnoreCase(notices, from);
        if (fromNotices != null) {
            List<String> pending = notices.remove(fromNotices);
            movedNotices = pending == null ? 0 : pending.size();
            if (movedNotices > 0) {
                String toNotices = keyIgnoreCase(notices, to);
                List<String> target = notices.computeIfAbsent(
                        toNotices == null ? to : toNotices, key -> new ArrayList<>());
                target.addAll(pending);
                trimNotices(target);
            }
        }

        boolean limitMoved = false;
        String fromLimit = keyIgnoreCase(limits, from);
        if (fromLimit != null) {
            Integer override = limits.remove(fromLimit);
            if (override != null) {
                limits.put(to, override);
                limitMoved = true;
            }
        }

        if (movedClaims > 0 || movedTreasury > 0 || movedNotices > 0 || limitMoved) {
            touch();
        }
        return new TransferSummary(movedClaims, movedTreasury, movedNotices, limitMoved);
    }

    private static <V> String keyIgnoreCase(Map<String, V> map, String name) {
        if (name == null) {
            return null;
        }
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- stats --

    public synchronized void addDiamondsSpent(long amount) {
        if (amount <= 0) {
            return;
        }
        diamondsSpent += amount;
        touch();
    }

    public synchronized void addTaxBurned(long amount) {
        if (amount <= 0) {
            return;
        }
        taxBurned += amount;
        touch();
    }

    public synchronized long chunksClaimedTotal() {
        return chunksClaimedTotal;
    }

    public synchronized long diamondsSpent() {
        return diamondsSpent;
    }

    public synchronized long taxBurned() {
        return taxBurned;
    }

    // ----------------------------------------------------------- helpers ----

    /** Разбор роли из JSON/команды; неизвестное значение — {@code null}. */
    static Claim.Role parseRole(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ACCESS", "ДОСТУП" -> Claim.Role.ACCESS;
            case "CONTAINER", "СУНДУКИ", "КОНТЕЙНЕРЫ" -> Claim.Role.CONTAINER;
            case "BUILD", "ПОЛНЫЙ", "ПОЛНЫЙ_ДОСТУП" -> Claim.Role.BUILD;
            default -> null;
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
