package ru.cherdakmd.chrdkgenerator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Данные личных генераторов ({@code plugins/CHRDK_Generator/generator.json}).
 *
 * <p>Ключ записи — UUID игрока; у игрока до трёх генераторов (слоты 0…2):
 * базовый, за перерождение и за платный ранг (п.31, §3). В каждой строке
 * лежат бинд ядра (UUID предмета), location установленного ядра, тир, уровни
 * модулей, бункер и метка последнего расчёта (п.31, §10). Снятие ядра снимает
 * бинд и выдаёт бункер, но сохраняет тир и модули; перерождение сбрасывает
 * тир и модули всех генераторов (ядра и бинды остаются, бункеры выдаются) —
 * п.31, §8. Перерождения, копилка денег и день выплаты — общие на игрока
 * (живут в строке слота 0).</p>
 *
 * <p>Формат версионирован ({@code schema = 1}) и проверяется до разбора:
 * целочисленность чисел, UUID-ключи, допустимые диапазоны тира и модулей,
 * единственный владелец каждого ядра (анти-дюп, п.31, §7). Значение игрока —
 * список строк (слот 0, слот 1, …); старый формат «одна строка-объект»
 * читается как один слот. Повреждённый файл переводит хранилище в карантин:
 * выдачи останавливаются, файл больше не перезаписывается — данные не
 * затираются мусором.</p>
 *
 * <p>Класс чистый (Gson + UUID + NIO): мутации на главном потоке, запись через
 * {@link SafeJsonWriter}; проверяется юнит-тестами без Bukkit.</p>
 */
public final class GeneratorStore {

    /** Максимальный тир ядра (п.31, §4). */
    public static final int MAX_TIER = 15;

    /** Модули с уровнями: скорость, ёмкость, удача — до 5 уровней (п.31, §5). */
    public static final int MAX_LEVEL = 5;

    /** Модуль «Чанк» — разовая покупка (п.31, §5). */
    public static final int CHUNK_LEVEL = 1;

    /** Известные модули; неизвестный ключ — повреждение файла. */
    public static final List<String> MODULES = List.of("speed", "capacity", "luck", "chunk");

    /** Потолок файла состояния (контракт хранилищ репозитория). */
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** Имя мира: скромный шаблон, чтобы мусор в JSON не доехал до Bukkit. */
    private static final String WORLD_PATTERN = "[A-Za-z0-9_.\\\\-]{1,64}";

    /** Итог установки ядра. */
    public enum InstallStatus {
        /** Ядро принято и заняло слот. */
        OK,
        /** Все доступные слоты заняты. */
        SLOTS_FULL
    }

    /** Результат установки: статус и занятый слот ({@code -1} — не принято). */
    public record InstallResult(InstallStatus status, int slot) {
    }

    /** Неизменяемый снимок записи для чтения (GUI, PAPI, команды). */
    public record Entry(UUID owner, int slot, UUID coreItem, String world, int x, int y, int z,
                        int tier, Map<String, Integer> modules, long bunker, long lastCalcMillis,
                        int rebirths, long fuelUsedMs, long money, long payoutDay) {
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
        Map<UUID, List<Row>> players = new LinkedHashMap<>();
        Map<UUID, Map<UUID, Progress>> carried = new LinkedHashMap<>();
        // Старые свободные строки не содержали ID ядра: сохраняем их отдельно, не угадывая владельца ядра.
        Map<UUID, Map<UUID, Progress>> legacyUnassigned = new LinkedHashMap<>();
    }

    private static final class Progress {
        int tier;
        Map<String, Integer> modules = new LinkedHashMap<>();
    }

    /** Запись одного генератора (слот = позиция в списке игрока). */
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
        int rebirths;
        long fuelUsedMs;
        long money;
        long payoutDay;
        long payoutSequence;
        long pendingPayout;
        boolean legacyPayoutReconciled;
        UUID containerLastMigration;
        ContainerMigration containerMigration;
        UUID containerCore;
        String containerTransition;
        UUID containerReleaseId;
        List<GeneratorDrops.Drop> legacyDrops;
        int legacyLuck;
        int lastSkillRebirth;
        boolean skillRebirthBaseline;
        int requestedSkillRebirth;
        /** Последний подтверждённый административный цикл Skills; null — старый цикл. */
        UUID skillResetEpoch;
        CoreTake coreTake;
    }

    record CoreTake(UUID transaction, UUID core, String before, String after) {
        CoreTake {
            if (core == null || transaction == null || before == null || after == null || before.equals(after)
                    || before.length() > 4 * 1024 * 1024 || after.length() > 4 * 1024 * 1024)
                throw new IllegalArgumentException("core take intent");
        }
    }

    /** Замороженный план: сохраняется до изменения второго файла. */
    record ContainerMigration(UUID transaction, UUID core, long units, long expectedRevision,
                              UUID previous, List<GeneratorContainerStore.Stack> afterSlots) {
        ContainerMigration {
            if (transaction == null || core == null || units <= 0 || expectedRevision < 0
                    || afterSlots == null || afterSlots.size() != GeneratorContainerStore.SLOTS)
                throw new IllegalArgumentException("invalid bunker migration");
            afterSlots = java.util.Collections.unmodifiableList(new ArrayList<>(afterSlots));
        }
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
            for (String progressField : List.of("carried", "legacyUnassigned")) if (row.has(progressField)) {
                if (!row.get(progressField).isJsonObject()) throw new IOException("carried map");
                for (var owner : row.getAsJsonObject(progressField).entrySet()) {
                    MilestoneMigration.uuid(owner.getKey());
                    if (!owner.getValue().isJsonObject()) throw new IOException("carried owner");
                    for (var core : owner.getValue().getAsJsonObject().entrySet()) {
                        MilestoneMigration.uuid(core.getKey());
                        if (!core.getValue().isJsonObject()) throw new IOException("carried core");
                        var progress = core.getValue().getAsJsonObject();
                        if (!progress.keySet().equals(Set.of("tier", "modules")) || !progress.get("modules").isJsonObject())
                            throw new IOException("carried fields");
                        progress.get("tier").getAsBigDecimal().intValueExact();
                        if (!progress.get("tier").isJsonPrimitive() || !progress.get("tier").getAsJsonPrimitive().isNumber()) throw new IOException("carried tier");
                        for (var module : progress.getAsJsonObject("modules").entrySet()) {
                            number(module.getValue()); module.getValue().getAsBigDecimal().intValueExact();
                        }
                    }
                }
            }
            for (var entry : row.getAsJsonObject("players").entrySet()) {
                MilestoneMigration.uuid(entry.getKey());
                JsonElement value = entry.getValue();
                JsonArray rows;
                if (value.isJsonObject()) {
                    // Старый формат: одна строка-объект — читается как слот 0.
                    rows = new JsonArray();
                    rows.add(value);
                } else if (value.isJsonArray()) {
                    rows = value.getAsJsonArray();
                } else {
                    throw new IOException("generator row");
                }
                if (rows.isEmpty()) {
                    throw new IOException("generator rows empty");
                }
                for (JsonElement element : rows) {
                    if (!element.isJsonObject()) {
                        throw new IOException("generator row");
                    }
                    var player = element.getAsJsonObject();
                    for (String key : List.of("tier", "modules", "bunker", "lastCalcMillis", "x", "y", "z")) {
                        if (!player.has(key) || player.get(key).isJsonNull()) {
                            throw new IOException("missing generator " + key);
                        }
                    }
                    for (String key : List.of("tier", "bunker", "lastCalcMillis", "x", "y", "z")) {
                        number(player.get(key));
                    }
                    for (String key : List.of("rebirths", "fuelUsedMs", "money", "payoutDay", "payoutSequence", "pendingPayout", "lastSkillRebirth")) {
                        if (player.has(key) && !player.get(key).isJsonNull()) {
                            number(player.get(key));
                        }
                    }
                    if (player.has("legacyPayoutReconciled")
                            && (!player.get("legacyPayoutReconciled").isJsonPrimitive()
                            || !player.get("legacyPayoutReconciled").getAsJsonPrimitive().isBoolean())) {
                        throw new IOException("generator payout migration flag");
                    }
                    if (!player.get("modules").isJsonObject()) {
                        throw new IOException("generator modules");
                    }
                    for (var module : player.getAsJsonObject("modules").entrySet()) {
                        number(module.getValue());
                    }
                    if (player.has("containerLastMigration") && !player.get("containerLastMigration").isJsonNull()) {
                        MilestoneMigration.uuid(player.get("containerLastMigration").getAsString());
                    }
                    if (player.has("skillResetEpoch") && !player.get("skillResetEpoch").isJsonNull()) {
                        if (!player.get("skillResetEpoch").isJsonPrimitive()
                                || !player.get("skillResetEpoch").getAsJsonPrimitive().isString()) throw new IOException("skill reset epoch type");
                        MilestoneMigration.uuid(player.get("skillResetEpoch").getAsString());
                    }
                    if (player.has("containerCore") && !player.get("containerCore").isJsonNull()) {
                        if (!player.get("containerCore").isJsonPrimitive() || !player.get("containerCore").getAsJsonPrimitive().isString()) throw new IOException("container core");
                        MilestoneMigration.uuid(player.get("containerCore").getAsString());
                    }
                    if (player.has("containerReleaseId") && !player.get("containerReleaseId").isJsonNull()) {
                        if (!player.get("containerReleaseId").isJsonPrimitive() || !player.get("containerReleaseId").getAsJsonPrimitive().isString()) throw new IOException("container release id");
                        MilestoneMigration.uuid(player.get("containerReleaseId").getAsString());
                    }
                    if (player.has("containerTransition") && !player.get("containerTransition").isJsonNull()
                            && (!player.get("containerTransition").isJsonPrimitive() || !player.get("containerTransition").getAsJsonPrimitive().isString()))
                        throw new IOException("container transition");
                    for (String field : List.of("legacyLuck", "lastSkillRebirth", "requestedSkillRebirth")) {
                        if (player.has(field) && !player.get(field).isJsonNull()) {
                            number(player.get(field)); player.get(field).getAsBigDecimal().intValueExact();
                        }
                    }
                    if (player.has("legacyDrops") && !player.get("legacyDrops").isJsonNull()) {
                        if (!player.get("legacyDrops").isJsonArray()) throw new IOException("legacy drops type");
                        for (var elementDrop : player.getAsJsonArray("legacyDrops")) {
                            if (!elementDrop.isJsonObject()) throw new IOException("legacy drop type");
                            var drop = elementDrop.getAsJsonObject();
                            if (!drop.keySet().equals(Set.of("material", "weight", "rare"))
                                    || !drop.get("material").isJsonPrimitive() || !drop.get("material").getAsJsonPrimitive().isString()
                                    || !drop.get("rare").isJsonPrimitive() || !drop.get("rare").getAsJsonPrimitive().isBoolean())
                                throw new IOException("legacy drop fields");
                            number(drop.get("weight"));
                            drop.get("weight").getAsBigDecimal().intValueExact();
                        }
                    }
                    if (player.has("skillRebirthBaseline") && (!player.get("skillRebirthBaseline").isJsonPrimitive()
                            || !player.get("skillRebirthBaseline").getAsJsonPrimitive().isBoolean())) throw new IOException("rebirth baseline type");
                    if (player.has("coreTake") && !player.get("coreTake").isJsonNull()) {
                        var take = player.get("coreTake");
                        if (!take.isJsonObject() || !take.getAsJsonObject().keySet().equals(Set.of("transaction", "core", "before", "after"))) throw new IOException("core take fields");
                        for (String field : List.of("transaction", "core", "before", "after")) {
                            var fieldValue = take.getAsJsonObject().get(field);
                            if (!fieldValue.isJsonPrimitive() || !fieldValue.getAsJsonPrimitive().isString()) throw new IOException("core take field type");
                        }
                        MilestoneMigration.uuid(take.getAsJsonObject().get("transaction").getAsString());
                        MilestoneMigration.uuid(take.getAsJsonObject().get("core").getAsString());
                    }
                    if (player.has("containerMigration") && !player.get("containerMigration").isJsonNull()) {
                        var migration = player.get("containerMigration").getAsJsonObject();
                        if (!migration.keySet().equals(Set.of("transaction", "core", "units", "expectedRevision", "afterSlots"))
                                && !migration.keySet().equals(Set.of("transaction", "core", "units", "expectedRevision", "previous", "afterSlots")))
                            throw new IOException("generator migration fields");
                        MilestoneMigration.uuid(migration.get("transaction").getAsString());
                        MilestoneMigration.uuid(migration.get("core").getAsString());
                        if (migration.has("previous") && !migration.get("previous").isJsonNull()) MilestoneMigration.uuid(migration.get("previous").getAsString());
                        number(migration.get("units"));
                        number(migration.get("expectedRevision"));
                        if (!migration.get("afterSlots").isJsonArray()) throw new IOException("generator migration slots");
                        for (var item : migration.getAsJsonArray("afterSlots")) {
                            if (item.isJsonNull()) continue;
                            var stack = item.getAsJsonObject();
                            if (!stack.keySet().equals(Set.of("material", "amount"))
                                    || !stack.get("material").isJsonPrimitive()
                                    || !stack.get("material").getAsJsonPrimitive().isString()) throw new IOException("generator migration stack");
                            number(stack.get("amount"));
                            stack.get("amount").getAsBigDecimal().intValueExact();
                        }
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
                entry.setValue(rows);
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
        if (state == null || state.schema != 1 || state.lineage == null || state.players == null || state.carried == null || state.legacyUnassigned == null) {
            throw new IllegalArgumentException("generator schema");
        }
        if (!state.registered && !state.players.isEmpty()) {
            throw new IllegalArgumentException("generator unregistered");
        }
        for (var collection : List.of(state.carried, state.legacyUnassigned)) for (var owner : collection.entrySet()) {
            if (owner.getKey() == null || !state.players.containsKey(owner.getKey()) || owner.getValue() == null || owner.getValue().size() > 256)
                throw new IllegalArgumentException("carried owner");
            for (var core : owner.getValue().entrySet()) {
                var progress = core.getValue();
                if (core.getKey() == null || progress == null || progress.tier < 1 || progress.tier > MAX_TIER || progress.modules == null)
                    throw new IllegalArgumentException("carried progress");
                for (var module : progress.modules.entrySet())
                    if (module.getKey() == null || module.getValue() == null || module.getValue() < 0 || module.getValue() > maxLevel(module.getKey()))
                        throw new IllegalArgumentException("carried module");
            }
        }
        Set<UUID> cores = new HashSet<>();
        for (var entry : state.players.entrySet()) {
            UUID owner = entry.getKey();
            List<Row> rows = entry.getValue();
            if (owner == null || rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("generator entry");
            }
            for (Row row : rows) {
                if (row == null || row.tier < 1 || row.tier > MAX_TIER
                        || row.bunker < 0 || row.lastCalcMillis < 0 || row.modules == null
                        || row.rebirths < 0 || row.lastSkillRebirth < 0 || row.requestedSkillRebirth < 0
                        || (row.requestedSkillRebirth > 0 && (!row.skillRebirthBaseline || row.requestedSkillRebirth <= row.lastSkillRebirth))
                        || row.fuelUsedMs < 0 || row.money < 0 || row.payoutDay < 0
                        || row.payoutSequence < 0 || row.pendingPayout < 0
                        || row.pendingPayout > row.money || row.pendingPayout > 1_000_000
                        || (row.pendingPayout > 0 && (!row.legacyPayoutReconciled
                        || row.payoutSequence == Long.MAX_VALUE))) {
                    throw new IllegalArgumentException("generator entry");
                }
                if (row.coreTake != null && (!row.coreTake.core().equals(row.coreItem) || row.containerCore != null
                        || row.containerMigration != null || row.bunker != 0)) throw new IllegalArgumentException("core take state");
                if (row.containerCore != null && !row.containerCore.equals(row.coreItem))
                    throw new IllegalArgumentException("container core mismatch");
                if (row.containerTransition != null && (row.containerCore == null
                        || (!row.containerTransition.equals("CREATE") && !row.containerTransition.equals("RELEASE"))
                        || row.containerMigration != null)) throw new IllegalArgumentException("container transition state");
                if ((row.containerReleaseId != null) != "RELEASE".equals(row.containerTransition))
                    throw new IllegalArgumentException("container release receipt");
                if (row.legacyLuck < 0 || row.legacyLuck > MAX_LEVEL
                        || (row.legacyDrops == null && row.legacyLuck != 0)
                        || (row.legacyDrops != null && (row.legacyDrops.isEmpty() || row.containerCore == null || row.bunker == 0)))
                    throw new IllegalArgumentException("legacy drop state");
                if (row.legacyDrops != null) {
                    long weight = 0;
                    for (var drop : row.legacyDrops) {
                        if (drop == null || drop.material() == null || !drop.material().matches("[A-Z0-9_]{1,128}") || drop.weight() <= 0)
                            throw new IllegalArgumentException("legacy drop");
                        weight += GeneratorDrops.effectiveWeight(drop, row.legacyLuck);
                        if (weight > Integer.MAX_VALUE) throw new IllegalArgumentException("legacy drop weights");
                    }
                }
                if (row.containerMigration != null) {
                    var migration = row.containerMigration;
                    if (!migration.core().equals(row.coreItem) || migration.units() > row.bunker
                            || !java.util.Objects.equals(migration.previous(), row.containerLastMigration))
                        throw new IllegalArgumentException("generator migration state");
                    for (var stack : migration.afterSlots()) if (stack != null)
                        new GeneratorContainerStore.Stack(stack.material(), stack.amount());
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

    private List<Row> rows(UUID owner) {
        return data.players.get(owner);
    }

    private Row row(UUID owner, int slot) {
        List<Row> rows = rows(owner);
        if (rows == null || slot < 0 || slot >= rows.size()) {
            return null;
        }
        return rows.get(slot);
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

    /** Снимок основного (слот 0) генератора; {@code null} — записи нет. */
    public synchronized Entry entry(UUID owner) {
        return entry(owner, 0);
    }

    /** Снимок генератора в слоте; {@code null} — слота нет. */
    public synchronized Entry entry(UUID owner, int slot) {
        Row row = row(owner, slot);
        if (row == null) {
            return null;
        }
        Row primary = row(owner, 0);
        return snapshot(owner, slot, row, primary != null ? primary : row);
    }

    /** Снимки всех генераторов игрока по порядку слотов. */
    public synchronized List<Entry> entries(UUID owner) {
        List<Row> rows = rows(owner);
        if (rows == null) {
            return List.of();
        }
        Row primary = rows.get(0);
        List<Entry> out = new ArrayList<>(rows.size());
        for (int slot = 0; slot < rows.size(); slot++) {
            out.add(snapshot(owner, slot, rows.get(slot), primary));
        }
        return List.copyOf(out);
    }

    /** Все владельцы записей (диагностика, админские команды). */
    public synchronized Set<UUID> owners() {
        return Set.copyOf(data.players.keySet());
    }

    public synchronized int size() {
        return data.players.size();
    }

    private static Entry snapshot(UUID owner, int slot, Row row, Row primary) {
        return new Entry(owner, slot, row.coreItem, row.world, row.x, row.y, row.z,
                row.tier, row.modules, row.bunker, row.lastCalcMillis,
                primary.rebirths, row.fuelUsedMs, primary.money, primary.payoutDay);
    }

    /**
     * Устанавливает ядро в первый свободный слот (или создаёт строку, пока
     * слотов меньше {@code maxSlots}): бинд на предмет и location, метка
     * расчёта. Первая установка создаёт запись с тиром 1. Повторная установка
     * того же ядра без снятия — ошибка анти-дюпа (п.31, §3, §7).
     */
    public synchronized InstallResult install(UUID owner, UUID coreItem, String world,
                                              int x, int y, int z, long now, int maxSlots) {
        writable();
        if (owner == null || coreItem == null || world == null || !world.matches(WORLD_PATTERN)) {
            throw new IllegalArgumentException("generator install arguments");
        }
        if (maxSlots < 1) {
            throw new IllegalArgumentException("generator max slots: " + maxSlots);
        }
        for (List<Row> otherRows : data.players.values()) {
            for (Row other : otherRows) {
                if (coreItem.equals(other.coreItem)) {
                    throw new IllegalArgumentException("generator duplicated core");
                }
            }
        }
        List<Row> rows = data.players.get(owner);
        if (rows == null) {
            rows = new ArrayList<>();
            data.players.put(owner, rows);
        }
        int slot = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).coreItem == null) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            if (rows.size() >= maxSlots) {
                return new InstallResult(InstallStatus.SLOTS_FULL, -1);
            }
            rows.add(new Row());
            slot = rows.size() - 1;
        }
        Row row = rows.get(slot);
        if (row.tier != 1 || !row.modules.isEmpty()) {
            var legacy = data.legacyUnassigned.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
            if (legacy.size() >= 256) throw new IllegalStateException("legacy generator progress archive full");
            var archive = new Progress(); archive.tier = row.tier; archive.modules = new LinkedHashMap<>(row.modules);
            legacy.put(UUID.randomUUID(), archive);
        }
        var saved = data.carried.get(owner);
        var progress = saved == null ? null : saved.remove(coreItem);
        if (saved != null && saved.isEmpty()) data.carried.remove(owner);
        row.tier = progress == null ? 1 : progress.tier;
        row.modules = progress == null ? new LinkedHashMap<>() : new LinkedHashMap<>(progress.modules);
        row.coreItem = coreItem;
        row.world = world;
        row.x = x;
        row.y = y;
        row.z = z;
        row.lastCalcMillis = Math.max(0L, now);
        dirty = true;
        return new InstallResult(InstallStatus.OK, slot);
    }

    /**
     * Снимает ядро слота: бинд и location снимаются, бункер выдаётся игроку и
     * обнуляется. Тир и модули сохраняются (п.31, §3, §8).
     *
     * @return сколько единиц бункера выдать игроку; {@code -1} — снимать нечего
     */
    public synchronized long remove(UUID owner, int slot, long now) {
        writable();
        Row row = row(owner, slot);
        if (row != null && (row.containerMigration != null || row.containerCore != null || row.coreTake != null)) throw new IllegalStateException("generator container bound");
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
    public synchronized void setTier(UUID owner, int slot, int tier) {
        writable();
        Row row = row(owner, slot);
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
    public synchronized void setModuleLevel(UUID owner, int slot, String module, int level) {
        writable();
        Row row = row(owner, slot);
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
     * Начисляет продукцию в бункер генератора (онлайн-цикл или офлайн-накопление)
     * и обновляет метку последнего расчёта. Переполнение не копится: потолок
     * бункера считает вызывающая логика (п.31, §6).
     *
     * @return новый объём бункера
     */
    public synchronized long addBunker(UUID owner, int slot, long units, long now) {
        writable();
        if (units < 0) {
            throw new IllegalArgumentException("generator bunker units: " + units);
        }
        Row row = row(owner, slot);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        if (row.containerMigration != null || row.containerCore != null) throw new IllegalStateException("generator container bound");
        row.bunker = units > Long.MAX_VALUE - row.bunker ? Long.MAX_VALUE : row.bunker + units;
        row.lastCalcMillis = Math.max(0L, now);
        dirty = true;
        return row.bunker;
    }

    /**
     * Записывает производство и расход топлива под одной блокировкой, чтобы
     * фоновый снимок не сохранил продукцию без соответствующего расхода.
     * Это не транзакция с миром Minecraft или платной автозаправкой.
     */
    public synchronized long addBunkerWithFuel(UUID owner, int slot, long units,
                                               long now, long fuelUsedMs) {
        writable();
        if (units < 0 || now < 0 || fuelUsedMs < 0) {
            throw new IllegalArgumentException("generator production or fuel");
        }
        Row row = row(owner, slot);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        if (row.containerMigration != null || row.containerCore != null) throw new IllegalStateException("generator container bound");
        row.bunker = units > Long.MAX_VALUE - row.bunker ? Long.MAX_VALUE : row.bunker + units;
        row.lastCalcMillis = now;
        row.fuelUsedMs = fuelUsedMs;
        dirty = true;
        return row.bunker;
    }

    /** Забирает продукцию из бункера генератора («забрать всё»). */
    public synchronized long withdrawBunker(UUID owner, int slot, long amount) {
        writable();
        if (amount < 0) {
            throw new IllegalArgumentException("generator withdraw: " + amount);
        }
        Row row = row(owner, slot);
        if (row == null || amount == 0) {
            return 0L;
        }
        if (row.containerMigration != null || row.containerCore != null) throw new IllegalStateException("generator container bound");
        long taken = Math.min(row.bunker, amount);
        if (taken > 0) {
            row.bunker -= taken;
            dirty = true;
        }
        return taken;
    }

    /**
     * Перерождение: бункеры всех генераторов выдаются игроку, тиры
     * сбрасываются к 1, модули к нулю; ядра и бинды остаются (п.31, §8).
     *
     * @return сколько единиц бункера выдать игроку всего; {@code -1} — записей нет
     */
    public synchronized long rebirth(UUID owner) {
        writable();
        List<Row> rows = rows(owner);
        if (rows == null) {
            return -1L;
        }
        long handed = 0L;
        for (Row row : rows) if (row.containerMigration != null || row.containerCore != null)
            throw new IllegalStateException("generator container bound");
        for (Row row : rows) {
            handed += row.bunker;
            row.bunker = 0L;
            row.tier = 1;
            row.modules.clear();
        }
        resetCarried(owner);
        Row primary = rows.get(0);
        if (primary.rebirths < Integer.MAX_VALUE) {
            primary.rebirths++;
        }
        dirty = true;
        return handed;
    }

    /** Первичная отметка не воспроизводит исторические перерождения. */
    synchronized boolean observeSkillRebirth(UUID owner, int count) {
        writable();
        Row row = row(owner, 0);
        if (row == null || count < 0) return false;
        if (!row.skillRebirthBaseline) {
            row.lastSkillRebirth = count; row.skillRebirthBaseline = true; dirty = true;
        } else if (count < row.lastSkillRebirth || (row.requestedSkillRebirth > 0 && count < row.requestedSkillRebirth)) {
            return false;
        } else if (count > row.lastSkillRebirth && count > row.requestedSkillRebirth) {
            row.requestedSkillRebirth = count; dirty = true;
        }
        return payoutCheckpoint();
    }

    synchronized boolean requestSkillRebirth(UUID owner, int count) {
        writable();
        Row row = row(owner, 0);
        if (row == null || count <= 0) return false;
        if (!row.skillRebirthBaseline) {
            row.lastSkillRebirth = count - 1; row.skillRebirthBaseline = true; dirty = true;
        }
        if (count > row.lastSkillRebirth && count > row.requestedSkillRebirth) {
            row.requestedSkillRebirth = count; dirty = true;
        }
        return payoutCheckpoint();
    }

    /**
     * Примитив будущего межплагинного сброса. Вызывающая сторона обязана сначала
     * устойчиво сохранить намерение и завершить расчёт/переносы контейнеров.
     * Пока этот метод не подключён к команде: сам по себе он не транзакция Skills.
     */
    synchronized boolean resetSkillCycle(UUID owner, UUID expectedEpoch, UUID nextEpoch, int expectedCount) {
        writable();
        if (owner == null || nextEpoch == null || nextEpoch.equals(expectedEpoch) || expectedCount < 0) return false;
        List<Row> rows = rows(owner);
        if (rows == null) {
            if (expectedEpoch != null) return false;
            rows = new ArrayList<>();
            Row initial = new Row();
            initial.skillRebirthBaseline = true;
            initial.lastSkillRebirth = expectedCount;
            rows.add(initial);
            data.players.put(owner, rows);
            dirty = true;
        }
        if (rows.isEmpty()) return false;
        Row primary = rows.get(0);
        if (nextEpoch.equals(primary.skillResetEpoch)) return payoutCheckpoint();
        if (!java.util.Objects.equals(expectedEpoch, primary.skillResetEpoch)
                || !primary.skillRebirthBaseline || primary.lastSkillRebirth != expectedCount
                || primary.requestedSkillRebirth != 0) return false;
        for (Row row : rows) {
            if (row.containerTransition != null || row.containerMigration != null || row.coreTake != null
                    || (row.bunker > 0 && (row.containerCore == null || row.legacyDrops == null))) return false;
        }
        for (Row row : rows) { row.tier = 1; row.modules.clear(); }
        resetCarried(owner);
        primary.rebirths = 0;
        primary.lastSkillRebirth = 0;
        primary.requestedSkillRebirth = 0;
        primary.skillResetEpoch = nextEpoch;
        dirty = true;
        return payoutCheckpoint();
    }

    private void resetCarried(UUID owner) {
        for (var collection : List.of(data.carried, data.legacyUnassigned)) {
            var saved = collection.get(owner);
            if (saved != null) for (var progress : saved.values()) { progress.tier = 1; progress.modules.clear(); }
        }
    }

    synchronized UUID skillResetEpoch(UUID owner) {
        Row primary = row(owner, 0);
        return primary == null ? null : primary.skillResetEpoch;
    }

    synchronized int requestedSkillRebirth(UUID owner) {
        Row row = row(owner, 0);
        return row == null ? 0 : row.requestedSkillRebirth;
    }

    /** Сброс с сохранением старого баланса и содержимого контейнеров; событие идемпотентно по номеру. */
    synchronized boolean rebirthKeepingBunker(UUID owner, int skillCount) {
        writable();
        List<Row> rows = rows(owner);
        if (rows == null || rows.isEmpty() || skillCount <= 0) return false;
        Row primary = rows.get(0);
        if (skillCount <= primary.lastSkillRebirth) return payoutCheckpoint();
        if (primary.requestedSkillRebirth > skillCount) return false;
        for (Row row : rows) {
            if (row.containerTransition != null || row.containerMigration != null
                    || (row.bunker > 0 && (row.containerCore == null || row.legacyDrops == null))) return false;
        }
        for (Row row : rows) { row.tier = 1; row.modules.clear(); }
        resetCarried(owner);
        int delta = primary.skillRebirthBaseline ? skillCount - primary.lastSkillRebirth : 1;
        primary.rebirths = (int) Math.min(Integer.MAX_VALUE, (long) primary.rebirths + delta);
        primary.lastSkillRebirth = skillCount;
        primary.skillRebirthBaseline = true;
        primary.requestedSkillRebirth = 0;
        dirty = true;
        return payoutCheckpoint();
    }

    /** Расход топлива текущего ведра генератора, миллисекунд работы (сбрасывается при замене лавы). */
    public synchronized void setFuelUsedMs(UUID owner, int slot, long fuelUsedMs) {
        writable();
        if (fuelUsedMs < 0) {
            throw new IllegalArgumentException("generator fuel: " + fuelUsedMs);
        }
        Row row = row(owner, slot);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        if (row.fuelUsedMs != fuelUsedMs) {
            row.fuelUsedMs = fuelUsedMs;
            dirty = true;
        }
    }

    /** Приход/расход денег генератора (дельта может быть отрицательной — выплата); копилка общая на игрока. */
    public synchronized long addMoney(UUID owner, long delta) {
        writable();
        Row row = row(owner, 0);
        if (row == null) {
            return -1L;
        }
        long value = Math.addExact(row.money, delta);
        if (value < row.pendingPayout) {
            throw new IllegalArgumentException("generator money: " + value);
        }
        if (row.money != value) {
            row.money = value;
            dirty = true;
        }
        return value;
    }

    /** День последней идемпотентной выплаты (0 — ещё не платили). */
    public synchronized void setPayoutDay(UUID owner, long payoutDay) {
        writable();
        if (payoutDay < 0) {
            throw new IllegalArgumentException("generator payout day: " + payoutDay);
        }
        Row row = row(owner, 0);
        if (row == null) {
            throw new IllegalStateException("generator missing row");
        }
        if (row.payoutDay != payoutDay) {
            row.payoutDay = payoutDay;
            dirty = true;
        }
    }

    /**
     * Итог успешной выплаты: деньги списываются и день фиксируется одной
     * записью — рестарт между списанием и отметкой дня невозможен.
     */
    public synchronized boolean takePayout(UUID owner, long amount, long day) {
        writable();
        if (amount < 0 || day < 0) {
            throw new IllegalArgumentException("generator payout: " + amount + "/" + day);
        }
        Row row = row(owner, 0);
        if (row == null || row.money < amount || row.payoutDay >= day) {
            return false;
        }
        row.money -= amount;
        row.payoutDay = day;
        dirty = true;
        return true;
    }

    record ContainerBinding(UUID core, String transition, UUID releaseId) { }

    synchronized CoreTake coreTake(UUID owner, int slot) {
        Row row = row(owner, slot);
        return row == null ? null : row.coreTake;
    }

    synchronized boolean prepareCoreTake(UUID owner, int slot, CoreTake intent) {
        writable();
        Row row = row(owner, slot);
        if (row == null || intent == null || !intent.core().equals(row.coreItem) || row.containerCore != null
                || row.containerMigration != null || row.containerLastMigration != null || row.bunker != 0 || row.coreTake != null) return false;
        for (Row other : rows(owner)) if (other.coreTake != null) return false;
        var saved = data.carried.get(owner);
        if (saved != null && (saved.size() >= 256 || saved.containsKey(intent.core()))) return false;
        row.coreTake = intent; dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean finishCoreTake(UUID owner, int slot, UUID transaction, long now) {
        writable();
        Row row = row(owner, slot);
        if (row == null || row.coreTake == null || !row.coreTake.transaction().equals(transaction) || now < 0) return false;
        var saved = data.carried.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        if (saved.size() >= 256 || saved.containsKey(row.coreItem)) return false;
        var progress = new Progress();
        progress.tier = row.tier; progress.modules = new LinkedHashMap<>(row.modules);
        saved.put(row.coreItem, progress);
        row.tier = 1; row.modules.clear();
        row.coreItem = null; row.world = null; row.coreTake = null; row.lastCalcMillis = now; dirty = true;
        return payoutCheckpoint();
    }

    synchronized ContainerBinding containerBinding(UUID owner, int slot) {
        Row row = row(owner, slot);
        return row == null || row.containerCore == null ? null : new ContainerBinding(row.containerCore, row.containerTransition, row.containerReleaseId);
    }

    synchronized boolean beginContainerBinding(UUID owner, int slot, UUID core, List<GeneratorDrops.Drop> drops, int luck) {
        writable();
        Row row = row(owner, slot);
        if (row == null || core == null || !core.equals(row.coreItem) || row.containerCore != null || row.coreTake != null
                || row.containerMigration != null || row.containerLastMigration != null) return false;
        if (row.bunker > 0) {
            if (drops == null || drops.isEmpty() || luck < 0 || luck > MAX_LEVEL) return false;
            long weight = 0;
            for (var drop : drops) {
                if (drop == null || drop.material() == null || !drop.material().matches("[A-Z0-9_]{1,128}") || drop.weight() <= 0) return false;
                weight += GeneratorDrops.effectiveWeight(drop, luck);
                if (weight > Integer.MAX_VALUE) return false;
            }
        }
        row.containerCore = core; row.containerTransition = "CREATE";
        if (row.bunker > 0) { row.legacyDrops = List.copyOf(drops); row.legacyLuck = luck; }
        dirty = true;
        return payoutCheckpoint();
    }

    record LegacyDrops(List<GeneratorDrops.Drop> drops, int luck) {
        LegacyDrops { drops = List.copyOf(drops); }
    }

    synchronized LegacyDrops legacyDrops(UUID owner, int slot) {
        Row row = row(owner, slot);
        return row == null || row.legacyDrops == null ? null : new LegacyDrops(row.legacyDrops, row.legacyLuck);
    }

    synchronized boolean finishContainerBinding(UUID owner, int slot, UUID core) {
        writable();
        Row row = row(owner, slot);
        if (row == null || !core.equals(row.containerCore) || !"CREATE".equals(row.containerTransition)) return false;
        row.containerTransition = null; dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean beginContainerRelease(UUID owner, int slot, UUID core) {
        writable();
        Row row = row(owner, slot);
        if (row == null || !core.equals(row.coreItem) || !core.equals(row.containerCore)
                || row.containerTransition != null || row.containerMigration != null || row.bunker != 0) return false;
        row.containerTransition = "RELEASE"; row.containerReleaseId = UUID.randomUUID(); dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean finishContainerRelease(UUID owner, int slot, UUID core) {
        writable();
        Row row = row(owner, slot);
        if (row == null || !core.equals(row.containerCore) || !"RELEASE".equals(row.containerTransition)) return false;
        row.containerCore = null; row.containerTransition = null; row.containerLastMigration = null;
        row.containerReleaseId = null; dirty = true;
        return payoutCheckpoint();
    }

    synchronized ContainerMigration containerMigration(UUID owner, int slot) {
        Row row = row(owner, slot);
        return row == null ? null : row.containerMigration;
    }

    synchronized UUID containerLastMigration(UUID owner, int slot) {
        Row row = row(owner, slot);
        return row == null ? null : row.containerLastMigration;
    }

    synchronized boolean prepareContainerMigration(UUID owner, int slot, ContainerMigration migration) {
        writable();
        Row row = row(owner, slot);
        if (row == null || row.containerMigration != null || row.containerTransition != null || migration == null
                || !migration.core().equals(row.coreItem) || migration.units() > row.bunker
                || !java.util.Objects.equals(migration.previous(), row.containerLastMigration)) return false;
        row.containerMigration = migration;
        dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean finishContainerMigration(UUID owner, int slot, UUID transaction) {
        writable();
        Row row = row(owner, slot);
        if (row == null || row.containerMigration == null
                || !row.containerMigration.transaction().equals(transaction)
                || row.bunker < row.containerMigration.units()) return false;
        row.bunker -= row.containerMigration.units();
        if (row.bunker == 0) { row.legacyDrops = null; row.legacyLuck = 0; }
        row.containerLastMigration = transaction;
        row.containerMigration = null;
        dirty = true;
        return payoutCheckpoint();
    }

    /** Состояние новой очереди выплат; деньги и старый день также принадлежат слоту 0. */
    record PayoutState(long money, long legacyDay, long sequence, long pending, boolean legacyReconciled) { }

    synchronized PayoutState payoutState(UUID owner) {
        Row row = row(owner, 0);
        return row == null ? null : new PayoutState(row.money, row.payoutDay,
                row.payoutSequence, row.pendingPayout, row.legacyPayoutReconciled);
    }

    /** Не начинать следующий внешний шаг, пока текущее состояние не сохранено. */
    synchronized boolean payoutCheckpoint() {
        writable();
        if (dirty || !Files.isRegularFile(file)) flush();
        return !dirty;
    }

    synchronized boolean reconcileLegacyPayout(UUID owner, long amount, long day) {
        Row row = row(owner, 0);
        if (row == null || row.legacyPayoutReconciled || row.pendingPayout != 0
                || amount <= 0 || row.money < amount || day <= row.payoutDay) return false;
        row.money -= amount;
        row.payoutDay = day;
        dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean finishLegacyReconciliation(UUID owner) {
        Row row = row(owner, 0);
        if (row == null || row.pendingPayout != 0) return false;
        if (!row.legacyPayoutReconciled) {
            row.legacyPayoutReconciled = true;
            dirty = true;
        }
        return payoutCheckpoint();
    }

    synchronized boolean preparePayout(UUID owner, long amount) {
        Row row = row(owner, 0);
        if (row == null || !row.legacyPayoutReconciled || row.pendingPayout != 0
                || row.payoutSequence == Long.MAX_VALUE || amount <= 0
                || amount > 1_000_000 || amount > row.money) return false;
        row.pendingPayout = amount;
        dirty = true;
        return payoutCheckpoint();
    }

    synchronized boolean finishPayout(UUID owner, long sequence, long amount) {
        Row row = row(owner, 0);
        if (row == null || row.payoutSequence != sequence || row.pendingPayout != amount
                || amount <= 0 || row.money < amount || sequence == Long.MAX_VALUE) return false;
        row.money -= amount;
        row.pendingPayout = 0;
        row.payoutSequence++;
        dirty = true;
        return payoutCheckpoint();
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
            dirty = true;
            throw new IllegalStateException("generator capacity");
        }
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Generator]")) {
            // Диск не принял данные — остаёмся «грязными», иначе следующее
            // saveNow() сочтёт файл записанным и данные потеряются.
            dirty = true;
        }
    }
}
