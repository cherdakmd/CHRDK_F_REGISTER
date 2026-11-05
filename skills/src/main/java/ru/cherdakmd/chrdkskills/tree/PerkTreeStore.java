package ru.cherdakmd.chrdkskills.tree;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Долговечное состояние деревьев перков: сами деревья, квитанции начисления
 * пыли и дневные счётчики. Приходит на смену in-memory
 * {@code PveAwardPowderRouter} из Фазы 1.
 *
 * <p>Файл {@code perk-trees.json} в папке плагина. Схема 1. Чтение строгое:
 * неизвестный домен, неизвестный ид узла, отрицательные числа или битый JSON
 * блокируют хранилище ({@code available == false}) и <b>не перезаписываются</b>
 * пустыми данными — так же, как {@code SkillAbilityStore} и {@code ClassStore}.
 * Запись идёт через {@link SafeJsonWriter} атомарно и по снимку.</p>
 *
 * <p>Квитанции и счётчики хранятся только за текущий день UTC; запас пыли и
 * открытые узлы живут между днями и рестартами. Все методы синхронизированы,
 * потому что сохранение вызывается из асинхронного пульса плагина.</p>
 */
public final class PerkTreeStore {

    static final int VERSION = 1;
    static final int MAX_PLAYERS = 200_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Object writeLock = new Object();
    private final Map<String, PerkTreeState> states = new LinkedHashMap<>();
    private final Map<String, Set<String>> receipts = new HashMap<>();
    private final Map<String, Long> credited = new HashMap<>();
    private String currentDay;
    private boolean dirty;
    private boolean available = true;

    public PerkTreeStore(Path folder, Logger logger) {
        this.file = folder.resolve("perk-trees.json");
        this.logger = logger;
    }

    private static String key(UUID player, TreeDomain domain) {
        return player + "|" + domain.id();
    }

    private static String dayKey(UUID player, String day) {
        return player + "|" + day;
    }

    public synchronized boolean available() {
        return available;
    }

    public synchronized int stateCount() {
        return states.size();
    }

    public synchronized PerkTreeState state(UUID player, TreeDomain domain) {
        return available ? states.get(key(player, domain)) : null;
    }

    public synchronized void putState(UUID player, PerkTreeState state) {
        if (!available || player == null || state == null) {
            return;
        }
        String key = key(player, state.domain());
        if (!states.containsKey(key) && states.size() >= MAX_PLAYERS) {
            return;
        }
        states.put(key, state);
        dirty = true;
    }

    public synchronized boolean hasReceipt(UUID player, String day, UUID deathId) {
        Set<String> mine = receipts.get(dayKey(player, day));
        return available && mine != null && mine.contains(deathId.toString());
    }

    public synchronized int receiptCount(UUID player, String day) {
        Set<String> mine = receipts.get(dayKey(player, day));
        return mine == null ? 0 : mine.size();
    }

    public synchronized void addReceipt(UUID player, String day, UUID deathId) {
        if (!available || deathId == null) {
            return;
        }
        receipts.computeIfAbsent(dayKey(player, day), ignored -> new HashSet<>())
                .add(deathId.toString());
        dirty = true;
    }

    public synchronized long creditedToday(UUID player, String day) {
        return available ? credited.getOrDefault(dayKey(player, day), 0L) : 0L;
    }

    public synchronized void addCredited(UUID player, String day, long amount) {
        if (!available || amount <= 0L) {
            return;
        }
        String key = dayKey(player, day);
        credited.put(key, credited.getOrDefault(key, 0L) + amount);
        dirty = true;
    }

    /** Новый день UTC: квитанции и счётчики сбрасываются, деревья остаются. */
    public synchronized void beginDay(String day) {
        if (!available || day.equals(currentDay)) {
            return;
        }
        currentDay = day;
        receipts.clear();
        credited.clear();
        dirty = true;
    }

    public synchronized void forget(UUID player) {
        String prefix = player.toString();
        states.keySet().removeIf(k -> k.startsWith(prefix + "|"));
        receipts.keySet().removeIf(k -> k.startsWith(prefix + "|"));
        credited.keySet().removeIf(k -> k.startsWith(prefix + "|"));
        dirty = true;
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsBigDecimal().intValueExact() != VERSION
                    || !root.get("states").isJsonObject()
                    || !root.get("receipts").isJsonObject()
                    || !root.get("credited").isJsonObject()) {
                throw new IllegalArgumentException("unsupported perk-trees schema");
            }
            Map<String, PerkTreeState> loadedStates = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject("states").entrySet()) {
                if (loadedStates.size() >= MAX_PLAYERS) {
                    throw new IllegalArgumentException("too many perk trees");
                }
                String composite = entry.getKey();
                int bar = composite.indexOf('|');
                if (bar < 1) {
                    throw new IllegalArgumentException("bad state key");
                }
                UUID player = UUID.fromString(composite.substring(0, bar));
                TreeDomain domain = domainOf(composite.substring(bar + 1));
                PerkTreeState state = readState(entry.getValue().getAsJsonObject(), domain);
                if (loadedStates.put(key(player, domain), state) != null) {
                    throw new IllegalArgumentException("duplicate perk tree");
                }
            }
            Map<String, Set<String>> loadedReceipts = new HashMap<>();
            for (var entry : root.getAsJsonObject("receipts").entrySet()) {
                Set<String> ids = new HashSet<>();
                for (var value : entry.getValue().getAsJsonArray()) {
                    ids.add(UUID.fromString(value.getAsString()).toString());
                }
                loadedReceipts.put(entry.getKey(), ids);
            }
            Map<String, Long> loadedCredited = new HashMap<>();
            for (var entry : root.getAsJsonObject("credited").entrySet()) {
                long amount = entry.getValue().getAsBigDecimal().longValueExact();
                if (amount < 0) {
                    throw new IllegalArgumentException("negative credited powder");
                }
                loadedCredited.put(entry.getKey(), amount);
            }
            String day = root.has("day") && !root.get("day").isJsonNull()
                    ? root.get("day").getAsString() : null;
            states.clear();
            states.putAll(loadedStates);
            receipts.clear();
            receipts.putAll(loadedReceipts);
            credited.clear();
            credited.putAll(loadedCredited);
            currentDay = day;
            dirty = false;
        } catch (Exception exception) {
            available = false;
            states.clear();
            receipts.clear();
            credited.clear();
            if (logger != null) {
                logger.severe("Деревья перков заблокированы: не удалось прочитать perk-trees.json. "
                        + "Файл не перезаписывается; восстановите резервную копию. "
                        + exception.getMessage());
            }
        }
    }

    private static TreeDomain domainOf(String id) {
        for (TreeDomain domain : TreeDomain.values()) {
            if (domain.id().equals(id)) {
                if (PerkTreeRegistry.of(domain).isEmpty()) {
                    throw new IllegalArgumentException("unregistered domain " + id);
                }
                return domain;
            }
        }
        throw new IllegalArgumentException("unknown domain " + id);
    }

    private static PerkTreeState readState(JsonObject row, TreeDomain domain) {
        int level = nonNegative(row, "level");
        int coreLevel = nonNegative(row, "coreLevel");
        long powder = nonNegativeLong(row, "powder");
        long powderSpent = nonNegativeLong(row, "powderSpent");
        long corePowderSpent = nonNegativeLong(row, "corePowderSpent");
        int tokensSpent = nonNegative(row, "tokensSpent");
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (!row.has("levels") || !row.get("levels").isJsonObject()) {
            throw new IllegalArgumentException("missing levels");
        }
        PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
        for (var entry : row.getAsJsonObject("levels").entrySet()) {
            PerkNode node = definition.node(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("unknown node " + entry.getKey()));
            int value = entry.getValue().getAsBigDecimal().intValueExact();
            if (value < 1 || value > node.maxLevel()) {
                throw new IllegalArgumentException("bad node rank " + entry.getKey());
            }
            levels.put(entry.getKey(), value);
        }
        String ability = null;
        if (row.has("selectedAbility") && !row.get("selectedAbility").isJsonNull()) {
            final String abilityId = row.get("selectedAbility").getAsString();
            PerkNode node = definition.node(abilityId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown ability " + abilityId));
            if (!node.ability() || !levels.containsKey(abilityId)) {
                throw new IllegalArgumentException("ability not unlocked " + abilityId);
            }
            ability = abilityId;
        }
        return new PerkTreeState(domain, level, coreLevel, powder, powderSpent,
                corePowderSpent, tokensSpent, levels, ability);
    }

    private static int nonNegative(JsonObject row, String field) {
        int value = row.get(field).getAsBigDecimal().intValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("negative " + field);
        }
        return value;
    }

    private static long nonNegativeLong(JsonObject row, String field) {
        long value = row.get(field).getAsBigDecimal().longValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("negative " + field);
        }
        return value;
    }

    private synchronized String snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("day", currentDay);
        JsonObject stateRows = new JsonObject();
        for (var entry : states.entrySet()) {
            PerkTreeState state = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("level", state.level());
            row.addProperty("coreLevel", state.coreLevel());
            row.addProperty("powder", state.powder());
            row.addProperty("powderSpent", state.powderSpent());
            row.addProperty("corePowderSpent", state.corePowderSpent());
            row.addProperty("tokensSpent", state.tokensSpent());
            row.add("levels", GSON.toJsonTree(state.levels()));
            if (state.selectedAbility() != null) {
                row.addProperty("selectedAbility", state.selectedAbility());
            }
            stateRows.add(entry.getKey(), row);
        }
        root.add("states", stateRows);
        JsonObject receiptRows = new JsonObject();
        for (var entry : receipts.entrySet()) {
            JsonArray ids = new JsonArray();
            entry.getValue().forEach(ids::add);
            receiptRows.add(entry.getKey(), ids);
        }
        root.add("receipts", receiptRows);
        root.add("credited", GSON.toJsonTree(credited));
        dirty = false;
        return GSON.toJson(root);
    }

    public void saveNow() {
        synchronized (writeLock) {
            String json;
            synchronized (this) {
                if (!available || !dirty) {
                    return;
                }
                json = snapshot();
            }
            if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Skills perk-trees]")) {
                synchronized (this) {
                    dirty = true;
                }
            }
        }
    }

    public void flush() {
        saveNow();
    }
}
