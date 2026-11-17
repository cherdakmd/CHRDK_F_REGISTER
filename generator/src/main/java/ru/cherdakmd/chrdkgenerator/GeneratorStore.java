package ru.cherdakmd.chrdkgenerator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import ru.cherdakmd.chrdkregister.api.MilestoneMigration;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Данные личного генератора ({@code plugins/CHRDK_Generator/generator.json}).
 *
 * <p>Ключ записи — UUID игрока: один активный генератор на игрока (п.31, §3).
 * В записи лежат бинд ядра (UUID предмета), location установленного ядра, тир,
 * уровни модулей, бункер и метка последнего расчёта (п.31, §10). Снятие ядра
 * снимает бинд и выдаёт бункер, но сохраняет тир и модули; перерождение
 * сбрасывает тир и модули (ядро и бинд остаются, бункер выдаётся) — п.31, §8.</p>
 *
 * <p>Формат версионирован ({@code schema = 1}) и проверяется до разбора:
 * целочисленность чисел, UUID-ключи, допустимые диапазоны тира и модулей,
 * единственный владелец каждого ядра (анти-дюп, п.31, §7). Повреждённый файл
 * переводит хранилище в карантин: выдачи останавливаются, файл больше не
 * перезаписывается — данные не затираются мусором.</p>
 *
 * <p>Класс чистый (Gson + UUID + NIO): мутации на главном потоке, запись через
 * {@link SafeJsonWriter}; проверяется юнит-тестами без Bukkit.</p>
 */
public final class GeneratorStore {

    /** Максимальный тир ядра (п.31, §4). */
    public static final int MAX_TIER = 7;

    /** Модули с уровнями: скорость, ёмкость, удача — до 5 уровней (п.31, §5). */
    public static final int MAX_LEVEL = 5;

    /** Модуль «Чанк» — разовая покупка (п.31, §5). */
    public static final int CHUNK_LEVEL = 1;

    /** Известные модули; неизвестный ключ — повреждение файла. */
    public static final List<String> MODULES = List.of("speed", "capacity", "luck", "chunk");

    /** Потолок файла состояния (контракт хранилищ репозитория). */
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** Имя мира: скромный шаблон, чтобы мусор в JSON не доехал до Bukkit. */
    private static final String WORLD_PATTERN = "[A-Za-z0-9_.\\-]{1,64}";

    /** Неизменяемый снимок записи для чтения (GUI, PAPI, команды). */
    public record Entry(UUID owner, UUID coreItem, String world, int x, int y, int z,
                        int tier, Map<String, Integer> modules, long bunker, long lastCalcMillis) {
        public Entry {
            modules = Map.copyOf(modules);
        }

        /** Ядро установлено в мире (бинд активен). */
        public boolean installed() {
            return coreItem != null;
        }
    }

    /** Состояние файла; Gson читает и пишет поля как есть. */
    private static final class Data {
        int schema = 1;
        UUID lineage = UUID.randomUUID();
        boolean registered;
        Map<UUID, Row> players = new LinkedHashMap<>();
    }

    /** Запись одного игрока; мутируется только методами хранилища. */
    private static final class Row {
        UUID coreItem;
        String world;
        int x;
        int y;
        int z;
        int tier = 1;
        Map<String, Integer> modules = new LinkedHashMap<>();
        long bunker;
        long lastCalcMillis;
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private Data data = new Data();
    private boolean healthy = true;
    private boolean dirty;

    public GeneratorStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("generator.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /**
     * Читает файл с полной проверкой; любой дефект — карантин без перезаписи.
     * Отсутствующий файл — норма: запись появится при регистрации.
     */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException("generator capacity");
            }
            var row = GeneratorJson.read(file);
            FinanceNumbers.requireIntegral(row);
            for (String key : List.of("schema", "lineage", "registered", "players")) {
                if (!row.has(key) || row.get(key).isJsonNull()) {
                    throw new IOException("missing generator " + key);
                }
            }
            if (!row.get("schema").isJsonPrimitive() || !row.get("schema").getAsJsonPrimitive().isNumber()
                    || !row.get("registered").isJsonPrimitive()
                    || !row.get("registered").getAsJsonPrimitive().isBoolean()) {
                throw new IOException("generator metadata type");
            }
            MilestoneMigration.uuid(row.get("lineage").getAsString());
            for (var entry : row.getAsJsonObject("players").entrySet()) {
                MilestoneMigration.uuid(entry.getKey());
                if (!entry.getValue().isJsonObject()) {
                    throw new IOException("generator row");
                }
                var player = entry.getValue().getAsJsonObject();
                for (String key : List.of("tier", "modules", "bunker", "lastCalcMillis", "x", "y", "z")) {
                    if (!player.has(key) || player.get(key).isJsonNull()) {
                        throw new IOException("missing generator " + key);
                    }
                }
                for (String key : List.of("tier", "bunker", "lastCalcMillis", "x", "y", "z")) {
                    number(player.get(key));
                }
                if (!player.get("modules").isJsonObject()) {
                    throw new IOException("generator modules");
                }
                for (var module : player.getAsJsonObject("modules").entrySet()) {
                    number(module.getValue());
                }
                if (player.has("coreItem") && !player.get("coreItem").isJsonNull()) {
                    MilestoneMigration.uuid(player.get("coreItem").getAsString());
                }
                if (player.has("world") && !player.get("world").isJsonNull()
                        && (!player.get("world").isJsonPrimitive()
                        || !player.get("world").getAsJsonPrimitive().isString())) {
                    throw new IOException("generator world");
                }
            }
            Data parsed = JSON.fromJson(row, Data.class);
            validate(parsed);
            data = parsed;
        } catch (Exception exception) {
            healthy = false;
            logger.log(Level.SEVERE,
                    "CHRDK_Generator: повреждён источник, выдачи остановлены; generator.json не перезаписывается",
                    exception);
        }
    }

    private static long number(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("generator number");
        }
        return value.getAsBigDecimal().longValueExact();
    }

    private static void validate(Data state) {
        if (state == null || state.schema != 1 || state.lineage == null || state.players == null) {
            throw new IllegalArgumentException("generator schema");
        }
        if (!state.registered && !state.players.isEmpty()) {
            throw new IllegalArgumentException("generator unregistered");
        }
        Set<UUID> cores = new HashSet<>();
        for (var entry : state.players.entrySet()) {
            UUID owner = entry.getKey();
            Row row = entry.getValue();
            if (owner == null || row == null || row.tier < 1 || row.tier > MAX_TIER
                    || row.bunker < 0 || row.lastCalcMillis < 0 || row.modules == null) {
                throw new IllegalArgumentException("generator entry");
            }
            for (var module : row.modules.entrySet()) {
                Integer level = module.getValue();
                if (module.getKey() == null || level == null || level < 0 || level > maxLevel(module.getKey())) {
                    throw new IllegalArgumentException("generator module");
                }
            }
            boolean bound = row.coreItem != null;
            if (bound != (row.world != null)) {
                throw new IllegalArgumentException("generator bind");
            }
            if (bound) {
                if (!row.world.matches(WORLD_PATTERN)) {
                    throw new IllegalArgumentException("generator world");
                }
                if (!cores.add(row.coreItem)) {
                    throw new IllegalArgumentException("generator duplicated core");
                }
            } else if (row.bunker != 0) {
                throw new IllegalArgumentException("generator detached bunker");
            }
        }
    }

    /** Верхняя граница уровня модуля; неизвестный модуль — ошибка. */
    public static int maxLevel(String module) {
        if ("chunk".equals(module)) {
            return CHUNK_LEVEL;
        }
        if (MODULES.contains(module)) {
            return MAX_LEVEL;
        }
        throw new IllegalArgumentException("generator unknown module: " + module);
    }

    private void writable() {
        if (!healthy) {
            throw new IllegalStateException("generator quarantined");
        }
    }

    private Row row(UUID owner) {
        return data.players.get(owner);
    }

    public synchronized boolean healthy() {
        return healthy;
    }

    public synchronized UUID lineage() {
        return data.lineage;
    }

    public synchronized boolean registered() {
        return data.registered;
    }

    /** Помечает источник зарегистрированным и сразу пишет файл. */
    public synchronized void markRegistered() throws IOException {
        writable();
        if (data.registered && Files.isRegularFile(file)) {
            return;
        }
        data.registered = true;
        dirty = true;
        flush();
    }

    /** Снимок записи; {@code null} — у игрока ещё нет генератора. */
    public synchronized Entry entry(UUID owner) {
        Row row = row(owner);
        return row == null ? null : snapshot(owner, row);
    }

    /** Все владельцы записей (диагностика, админские команды). */
    public synchronized Set<UUID> owners() {
        return Set.copyOf(data.players.keySet());
    }

    public synchronized int size() {
        return data.players.size();
    }

    private static Entry snapshot(UUID owner, Row row) {
        return new Entry(owner, row.coreItem, row.world, row.x, row.y, row.z,
                row.tier, row.modules, row.bunker, row.lastCalcMillis);
    }

    /**
     * Устанавливает ядро: бинд на предмет и location, метка расчёта. Первая
     * установка создаёт запись с тиром 1; повторная без снятия — отказ
     * (один активный генератор на игрока, п.31, §3).
     */
    public synchronized boolean install(UUID owner, UUID coreItem, String world,
                                        int x, int y, int z, long now) {
        writable();
        if (owner == null || coreItem == null || world == null || !world.matches(WORLD_PATTERN)) {
            throw new IllegalArgumentException("generator install arguments");
        }
        Row row = data.players.get(owner);
        if (row != null && row.coreItem != null) {
            return false;
        }
        for (Row other : data.players.values()) {
            if (coreItem.equals(other.coreItem)) {
                throw new IllegalArgumentException("generator duplicated core");
            }
        }
        if (row == null) {
            row = new Row();
            data.players.put(owner, row);
        }
        row.coreItem = coreItem;
        row.world = world;
        row.x = x;
        row.y = y;
        row.z = z;
        row.lastCalcMillis = Math.max(0L, now);
        dirty = true;
        return true;
    }

    /**
     * Снимает ядро: бинд и location снимаются, бункер выдаётся игроку и
     * обнуляется. Тир и модули сохраняются (п.31, §3, §8).
     *
     * @return сколько единиц бункера выдать игроку; {@code -1} — снимать нечего
     */
    public synchronized long remove(UUID owner, long now) {
        writable();
        Row row = row(owner);
        if (row == null || row.coreItem == null) {
            return -1L;
        }
        long handed = row.bunker;
        row.coreItem = null;
        row.world = null;
        row.bunker = 0L;
        row.lastCalcMillis = Math.max(0L, now);
        dirty = true;
        return handed;
    }

    /** Покупка/сброс тира; допустимы значения 1…{@value #MAX_TIER}. */
    public synchronized void setTier(UUID owner, int tier) {
        writable();
        Row row = row(owner);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        if (tier < 1 || tier > MAX_TIER) {
            throw new IllegalArgumentException("generator tier: " + tier);
        }
        if (row.tier != tier) {
            row.tier = tier;
            dirty = true;
        }
    }

    /** Уровень модуля: скорость/ёмкость/удача 0…5, «Чанк» 0…1. */
    public synchronized void setModuleLevel(UUID owner, String module, int level) {
        writable();
        Row row = row(owner);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        int max = maxLevel(module);
        if (level < 0 || level > max) {
            throw new IllegalArgumentException("generator module level: " + module + "=" + level);
        }
        if (level == 0) {
            if (row.modules.remove(module) != null) {
                dirty = true;
            }
        } else if (!Integer.valueOf(level).equals(row.modules.put(module, level))) {
            dirty = true;
        }
    }

    /**
     * Начисляет продукцию в бункер (онлайн-цикл или офлайн-накопление) и
     * обновляет метку последнего расчёта. Переполнение не копится: потолок
     * бункера считает вызывающая логика (п.31, §6).
     *
     * @return новый объём бункера
     */
    public synchronized long addBunker(UUID owner, long units, long now) {
        writable();
        if (units < 0) {
            throw new IllegalArgumentException("generator bunker units: " + units);
        }
        Row row = row(owner);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        row.bunker = units > Long.MAX_VALUE - row.bunker ? Long.MAX_VALUE : row.bunker + units;
        row.lastCalcMillis = Math.max(0L, now);
        dirty = true;
        return row.bunker;
    }

    /** Забирает продукцию из бункера («забрать всё»). */
    public synchronized long withdrawBunker(UUID owner, long amount) {
        writable();
        if (amount < 0) {
            throw new IllegalArgumentException("generator withdraw: " + amount);
        }
        Row row = row(owner);
        if (row == null || amount == 0) {
            return 0L;
        }
        long taken = Math.min(row.bunker, amount);
        if (taken > 0) {
            row.bunker -= taken;
            dirty = true;
        }
        return taken;
    }

    /**
     * Перерождение: бункер выдаётся игроку, тир сбрасывается к 1, модули к
     * нулю; ядро и бинд остаются (п.31, §8).
     *
     * @return сколько единиц бункера выдать игроку; {@code -1} — записи нет
     */
    public synchronized long rebirth(UUID owner) {
        writable();
        Row row = row(owner);
        if (row == null) {
            return -1L;
        }
        long handed = row.bunker;
        row.bunker = 0L;
        row.tier = 1;
        row.modules.clear();
        dirty = true;
        return handed;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок состояния для записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        dirty = false;
        return JSON.toJson(data);
    }

    /** Пишет файл, если есть изменения (или его ещё нет). */
    public synchronized void saveNow() {
        if (!healthy || !dirty && Files.isRegularFile(file)) {
            return;
        }
        flush();
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    public synchronized void flush() {
        writable();
        String json = snapshotJson();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IllegalStateException("generator capacity");
        }
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Generator]")) {
            // Диск не принял данные — остаёмся «грязными», иначе следующее
            // автосохранение решит, что писать нечего, и прогресс потеряется.
            dirty = true;
        }
    }
}
