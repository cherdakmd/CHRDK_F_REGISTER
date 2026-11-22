package ru.cherdakmd.chrdkgenerator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.api.FinanceNumbers;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Долговечное содержимое будущего общего окна. Все изменения контейнера и
 * состояния производства публикуются одним снимком; перенос в playerdata
 * проходит через intent и native witness, а не через Bukkit-событие клика.
 * Пока не подключён к действующему генератору.
 */
final class GeneratorContainerStore {
    static final int RESOURCE_SLOTS = 36;
    static final int FUEL_SLOTS = 9;
    static final int SLOTS = RESOURCE_SLOTS + FUEL_SLOTS;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    record Stack(String material, int amount) {
        Stack {
            if (material == null || !material.matches("[A-Z0-9_]{1,128}") || amount < 1 || amount > 64)
                throw new IllegalArgumentException("invalid generator container stack");
        }
    }
    record View(UUID core, long revision, List<Stack> slots, long burnMillis, long lastCalcMillis, boolean pending, UUID lastMigration) {
        View { slots = java.util.Collections.unmodifiableList(new ArrayList<>(slots)); }
    }

    record Sale(long sequence, long amount) {
        Sale {
            if (sequence <= 0 || amount <= 0 || amount > GeneratorPayoutCoordinator.MAX_OBLIGATION)
                throw new IllegalArgumentException("invalid container sale");
        }
    }

    private static final class Pending {
        UUID transaction;
        String beforePlayer;
        String afterPlayer;
        int index;
        String material;
        int delta;
    }
    private static final class Container {
        UUID owner;
        int slot;
        UUID core;
        long revision;
        List<Stack> slots = emptySlots();
        long burnMillis;
        long lastCalcMillis;
        UUID lastMigration;
        long saleSequence;
        Sale salePending;
        Pending pending;
    }
    private record Retired(UUID release, long saleSequence) {
        Retired { if (release == null || saleSequence < 0) throw new IllegalArgumentException("retired container"); }
    }
    private static final class Data {
        int schema = 1;
        Map<String, Container> containers = new LinkedHashMap<>();
        Map<String, Retired> retiredContainers = new LinkedHashMap<>();
    }

    private final Path file;
    private final Logger logger;
    private Data data = new Data();
    private boolean healthy = true;

    GeneratorContainerStore(Path folder, Logger logger) {
        this.file = folder.resolve("generator-containers.json");
        this.logger = logger;
    }

    synchronized boolean healthy() { return healthy; }
    private static String key(UUID owner, int slot) { return owner + ":" + slot; }
    private static List<Stack> emptySlots() {
        return new ArrayList<>(java.util.Collections.nCopies(SLOTS, null));
    }

    synchronized void load() {
        if (!healthy || Files.notExists(file)) return;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) throw new IOException("container capacity/type");
            var root = GeneratorJson.read(file);
            FinanceNumbers.requireIntegral(root);
            if (!root.has("schema") || !root.get("schema").isJsonPrimitive()
                    || !root.get("schema").getAsJsonPrimitive().isNumber()
                    || root.get("schema").getAsBigDecimal().intValueExact() != 1
                    || !root.has("containers") || !root.get("containers").isJsonObject())
                throw new IOException("container schema");
            validateJson(root);
            var loaded = JSON.fromJson(root, Data.class);
            validate(loaded);
            data = loaded;
        } catch (Exception error) {
            healthy = false;
            logger.severe("[CHRDK_Generator] Контейнеры в карантине: " + error.getMessage()
                    + "; не удаляйте generator-containers.json.");
        }
    }

    private static void validateJson(com.google.gson.JsonObject root) {
        if (!root.keySet().equals(java.util.Set.of("schema", "containers"))
                && !root.keySet().equals(java.util.Set.of("schema", "containers", "retiredContainers"))) throw new IllegalArgumentException("container root fields");
        if (root.has("retiredContainers")) {
            if (!root.get("retiredContainers").isJsonObject()) throw new IllegalArgumentException("retired containers type");
            for (var retired : root.getAsJsonObject("retiredContainers").entrySet()) {
                String[] ids = retired.getKey().split(":", -1);
                if (ids.length != 2 || !UUID.fromString(ids[0]).toString().equals(ids[0])
                        || !UUID.fromString(ids[1]).toString().equals(ids[1]) || !retired.getValue().isJsonObject())
                    throw new IllegalArgumentException("retired container key");
                var value = retired.getValue().getAsJsonObject();
                if (!value.keySet().equals(java.util.Set.of("release", "saleSequence"))
                        || !value.get("release").isJsonPrimitive() || !value.get("release").getAsJsonPrimitive().isString()
                        || !value.get("saleSequence").isJsonPrimitive() || !value.get("saleSequence").getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("retired container fields");
                String release = value.get("release").getAsString();
                if (!UUID.fromString(release).toString().equals(release) || value.get("saleSequence").getAsBigDecimal().longValueExact() < 0)
                    throw new IllegalArgumentException("retired container receipt");
            }
        }
        for (var entry : root.getAsJsonObject("containers").entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("container row type");
            var row = entry.getValue().getAsJsonObject();
            var required = java.util.Set.of("owner", "slot", "core", "revision", "slots", "burnMillis", "lastCalcMillis", "pending");
            var allowed = java.util.Set.of("owner", "slot", "core", "revision", "slots", "burnMillis", "lastCalcMillis", "pending", "lastMigration", "saleSequence", "salePending");
            if (!row.keySet().containsAll(required) || !allowed.containsAll(row.keySet())
                    || row.has("saleSequence") != row.has("salePending"))
                throw new IllegalArgumentException("container row fields");
            if (row.has("saleSequence")) {
                if (!row.get("saleSequence").isJsonPrimitive() || !row.get("saleSequence").getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("sale sequence");
                row.get("saleSequence").getAsBigDecimal().longValueExact();
                var sale = row.get("salePending");
                if (!sale.isJsonNull()) {
                    if (!sale.isJsonObject() || !sale.getAsJsonObject().keySet().equals(java.util.Set.of("sequence", "amount")))
                        throw new IllegalArgumentException("sale pending");
                    for (String field : List.of("sequence", "amount")) {
                        var value = sale.getAsJsonObject().get(field);
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("sale number");
                        value.getAsBigDecimal().longValueExact();
                    }
                }
            }
            if (row.has("lastMigration") && !row.get("lastMigration").isJsonNull()) {
                if (!row.get("lastMigration").isJsonPrimitive() || !row.get("lastMigration").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("migration id");
                String id = row.get("lastMigration").getAsString();
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("migration id canonical");
            }
            for (String field : List.of("owner", "core")) {
                if (!row.get(field).isJsonPrimitive() || !row.get(field).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("container id");
                String id = row.get(field).getAsString();
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("container id canonical");
            }
            for (String field : List.of("slot", "revision", "burnMillis", "lastCalcMillis")) {
                if (!row.get(field).isJsonPrimitive() || !row.get(field).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("container number");
            }
            row.get("slot").getAsBigDecimal().intValueExact();
            if (!row.get("slots").isJsonArray()) throw new IllegalArgumentException("container slots");
            for (var element : row.getAsJsonArray("slots")) {
                if (element.isJsonNull()) continue;
                if (!element.isJsonObject()) throw new IllegalArgumentException("container item");
                var item = element.getAsJsonObject();
                if (!item.keySet().equals(java.util.Set.of("material", "amount"))
                        || !item.get("material").isJsonPrimitive() || !item.get("material").getAsJsonPrimitive().isString()
                        || !item.get("amount").isJsonPrimitive() || !item.get("amount").getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("container item fields");
                item.get("amount").getAsBigDecimal().intValueExact();
            }
            var pendingValue = row.get("pending");
            if (!pendingValue.isJsonNull()) {
                if (!pendingValue.isJsonObject()) throw new IllegalArgumentException("container pending");
                var pending = pendingValue.getAsJsonObject();
                if (!pending.keySet().equals(java.util.Set.of("transaction", "beforePlayer", "afterPlayer", "index", "material", "delta")))
                    throw new IllegalArgumentException("container intent fields");
                for (String field : List.of("transaction", "beforePlayer", "afterPlayer", "material")) {
                    if (!pending.get(field).isJsonPrimitive() || !pending.get(field).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("container intent string");
                }
                for (String field : List.of("index", "delta")) {
                    if (!pending.get(field).isJsonPrimitive() || !pending.get(field).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("container intent number");
                    pending.get(field).getAsBigDecimal().intValueExact();
                }
                String transaction = pending.get("transaction").getAsString();
                if (!UUID.fromString(transaction).toString().equals(transaction)) throw new IllegalArgumentException("container intent id");
            }
        }
    }

    private static void validate(Data data) {
        if (data == null || data.schema != 1 || data.containers == null || data.retiredContainers == null) throw new IllegalArgumentException("container schema");
        for (var retired : data.retiredContainers.entrySet()) {
            String[] ids = retired.getKey().split(":", -1);
            if (ids.length != 2 || !UUID.fromString(ids[0]).toString().equals(ids[0])
                    || !UUID.fromString(ids[1]).toString().equals(ids[1]) || retired.getValue() == null)
                throw new IllegalArgumentException("retired container history");
            new Retired(retired.getValue().release(), retired.getValue().saleSequence());
        }
        var pendingOwners = new java.util.HashSet<UUID>();
        var cores = new java.util.HashSet<UUID>();
        for (var entry : data.containers.entrySet()) {
            Container row = entry.getValue();
            if (row == null || row.owner == null || row.core == null || row.slot < 0 || row.slot >= 3
                    || !key(row.owner, row.slot).equals(entry.getKey()) || row.revision < 0
                    || row.slots == null || row.slots.size() != SLOTS || row.burnMillis < 0 || row.lastCalcMillis < 0)
                throw new IllegalArgumentException("container row");
            if (row.saleSequence < 0 || (row.salePending != null && row.salePending.sequence() != row.saleSequence))
                throw new IllegalArgumentException("container sale state");
            if (data.retiredContainers.containsKey(row.owner + ":" + row.core)) throw new IllegalArgumentException("active and retired container history");
            if (!cores.add(row.core)) throw new IllegalArgumentException("duplicate container core");
            for (Stack item : row.slots) if (item != null) new Stack(item.material(), item.amount());
            if (row.pending != null) {
                if (!pendingOwners.add(row.owner)) throw new IllegalArgumentException("multiple pending transfers");
                validatePending(row, row.pending);
            }
        }
    }

    private static void validatePending(Container row, Pending p) {
        if (p.transaction == null || p.index < 0 || p.index >= SLOTS || p.delta == 0
                || p.delta < -64 || p.delta > 64 || (p.delta > 0 && p.index < RESOURCE_SLOTS))
            throw new IllegalArgumentException("container intent");
        new InventoryTransfer.Intent(p.transaction, row.owner, p.beforePlayer, p.afterPlayer);
        new Stack(p.material, Math.abs(p.delta));
        Stack before = row.slots.get(p.index);
        if (before != null && !before.material().equals(p.material)) throw new IllegalArgumentException("container item mismatch");
        int after = (before == null ? 0 : before.amount()) + p.delta;
        if (after < 0 || after > 64) throw new IllegalArgumentException("container quantity");
    }

    private Data copy() { return JSON.fromJson(JSON.toJson(data), Data.class); }
    private boolean publish(Data next) {
        if (!healthy) return false;
        validate(next);
        String json = JSON.toJson(next);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return false;
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Generator]")) return false;
        data = next;
        return true;
    }

    synchronized boolean create(UUID owner, int slot, UUID core, long now) {
        if (!healthy || owner == null || core == null || slot < 0 || slot >= 3 || now < 0) return false;
        Container existing = data.containers.get(key(owner, slot));
        if (existing != null) return existing.core.equals(core);
        for (Container row : data.containers.values()) if (row.core.equals(core)) return false;
        Data next = copy();
        Container row = new Container();
        row.owner = owner; row.slot = slot; row.core = core; row.lastCalcMillis = now;
        Retired previous = next.retiredContainers.remove(owner + ":" + core);
        if (previous != null) row.saleSequence = previous.saleSequence();
        next.containers.put(key(owner, slot), row);
        return publish(next);
    }

    synchronized View view(UUID owner, int slot) {
        Container row = data.containers.get(key(owner, slot));
        return !healthy || row == null ? null : new View(row.core, row.revision, row.slots,
                row.burnMillis, row.lastCalcMillis, row.pending != null, row.lastMigration);
    }

    /** Под release-intent удаляет только пустой контейнер; горящий остаток списывается. */
    synchronized boolean removeEmpty(UUID owner, int slot, UUID core, UUID expectedMigration, UUID release) {
        if (!healthy || release == null) return false;
        Container row = data.containers.get(key(owner, slot));
        if (row == null) {
            Retired receipt = data.retiredContainers.get(owner + ":" + core);
            return receipt != null && release.equals(receipt.release());
        }
        if (!row.core.equals(core) || row.pending != null || row.salePending != null
                || !java.util.Objects.equals(row.lastMigration, expectedMigration)
                || row.slots.stream().anyMatch(java.util.Objects::nonNull)) return false;
        Data next = copy();
        next.retiredContainers.put(owner + ":" + core, new Retired(release, row.saleSequence));
        next.containers.remove(key(owner, slot));
        return publish(next);
    }

    record SaleState(long sequence, Sale pending) { }

    synchronized SaleState saleState(UUID owner, int slot) {
        Container row = data.containers.get(key(owner, slot));
        return !healthy || row == null ? null : new SaleState(row.saleSequence, row.salePending);
    }

    /** Изъятие одной порции предметов и обязательство сохраняются одной записью. */
    synchronized boolean reserveSale(UUID owner, int slot, UUID core, long revision,
                                     int index, int amount, long price) {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null || !row.core.equals(core) || row.revision != revision
                || row.revision == Long.MAX_VALUE || row.saleSequence == Long.MAX_VALUE
                || row.pending != null || row.salePending != null || index < 0 || index >= RESOURCE_SLOTS
                || amount <= 0 || price <= 0 || price > GeneratorPayoutCoordinator.MAX_OBLIGATION) return false;
        Stack item = row.slots.get(index);
        if (item == null || amount > item.amount()) return false;
        Data next = copy();
        Container change = next.containers.get(key(owner, slot));
        change.slots.set(index, amount == item.amount() ? null : new Stack(item.material(), item.amount() - amount));
        change.saleSequence++;
        change.salePending = new Sale(change.saleSequence, price);
        change.revision++;
        return publish(next);
    }

    /** Квитанция банка проверяется вызывающим до подтверждения. */
    synchronized boolean finishSale(UUID owner, int slot, Sale sale) {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null || sale == null || !sale.equals(row.salePending) || row.revision == Long.MAX_VALUE) return false;
        Data next = copy();
        Container change = next.containers.get(key(owner, slot));
        change.salePending = null;
        change.revision++;
        return publish(next);
    }

    /** Идемпотентно принимает замороженную порцию старого числового бункера. */
    synchronized boolean applyMigration(UUID owner, int slot, GeneratorStore.ContainerMigration migration) {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null || migration == null || !row.core.equals(migration.core())) return false;
        if (migration.transaction().equals(row.lastMigration)) return true;
        if (row.pending != null || row.revision != migration.expectedRevision() || row.revision == Long.MAX_VALUE
                || !java.util.Objects.equals(row.lastMigration, migration.previous())) return false;
        long added = 0;
        for (int i = 0; i < SLOTS; i++) {
            Stack before = row.slots.get(i), after = migration.afterSlots().get(i);
            if (i >= RESOURCE_SLOTS) {
                if (!java.util.Objects.equals(before, after)) return false;
                continue;
            }
            if (before != null && (after == null || !before.material().equals(after.material()) || before.amount() > after.amount())) return false;
            if (after != null) added += after.amount() - (before == null ? 0 : before.amount());
        }
        if (added != migration.units()) return false;
        Data next = copy();
        Container change = next.containers.get(key(owner, slot));
        change.slots = new ArrayList<>(migration.afterSlots());
        change.lastMigration = migration.transaction();
        change.revision++;
        return publish(next);
    }

    /**
     * Атомарно меняет запас, остаток горения и метку производства. Вызывающий
     * обязан заранее проверить содержимое и вместимость, старую версию и core.
     */
    synchronized boolean settle(UUID owner, int slot, UUID core, long expectedRevision,
                                List<Stack> slots, long burnMillis, long lastCalcMillis) {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null || !row.core.equals(core) || row.pending != null
                || row.revision != expectedRevision || row.revision == Long.MAX_VALUE
                || slots == null || slots.size() != SLOTS || burnMillis < 0 || lastCalcMillis < row.lastCalcMillis)
            return false;
        Data next = copy();
        Container change = next.containers.get(key(owner, slot));
        change.slots = new ArrayList<>(slots);
        change.burnMillis = burnMillis;
        change.lastCalcMillis = lastCalcMillis;
        change.revision++;
        return publish(next);
    }

    /** Фиксирует intent до записи storage+nonce игрока. Положительный delta — только топливо. */
    synchronized boolean prepare(UUID owner, int slot, UUID core, long expectedRevision, UUID transaction,
                                 int index, String material, int delta, String beforePlayer, String afterPlayer) {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null || !row.core.equals(core) || row.pending != null
                || row.revision != expectedRevision || row.revision == Long.MAX_VALUE) return false;
        for (Container other : data.containers.values()) if (other.owner.equals(owner) && other.pending != null) return false;
        var pending = new Pending();
        pending.transaction = transaction; pending.index = index; pending.material = material;
        pending.delta = delta; pending.beforePlayer = beforePlayer; pending.afterPlayer = afterPlayer;
        try { validatePending(row, pending); }
        catch (RuntimeException invalid) { return false; }
        Data next = copy();
        next.containers.get(key(owner, slot)).pending = pending;
        return publish(next);
    }

    /** Повтор безопасен: после native witness публикуется только сохранённый intent. */
    synchronized InventoryTransfer.Decision recover(UUID owner, int slot, InventoryTransfer.Inventory inventory) throws IOException {
        Container row = data.containers.get(key(owner, slot));
        if (!healthy || row == null) return InventoryTransfer.Decision.CONFLICT;
        Pending p = row.pending;
        if (p == null) return InventoryTransfer.Decision.APPLIED;
        var intent = new InventoryTransfer.Intent(p.transaction, owner, p.beforePlayer, p.afterPlayer);
        var result = InventoryTransfer.recover(intent, inventory);
        if (result != InventoryTransfer.Decision.APPLIED) return result;
        Data next = copy();
        Container change = next.containers.get(key(owner, slot));
        Stack before = change.slots.get(p.index);
        int amount = (before == null ? 0 : before.amount()) + p.delta;
        change.slots.set(p.index, amount == 0 ? null : new Stack(p.material, amount));
        change.pending = null;
        change.revision++;
        if (!publish(next)) throw new IOException("generator container commit failed; intent retained");
        return InventoryTransfer.Decision.APPLIED;
    }
}
