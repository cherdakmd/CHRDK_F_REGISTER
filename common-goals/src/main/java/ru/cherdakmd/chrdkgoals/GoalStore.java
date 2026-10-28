package ru.cherdakmd.chrdkgoals;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/** Источник накопительных доходов. Schema 2 запрещает физическое снятие pendingRewards. */
public final class GoalStore implements ru.cherdakmd.chrdkregister.api.ChargeInventory.Source {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final class Data {
        int schema = 1;
        UUID incomeId;
        boolean bankRegistered;
        List<GoalState> goals = new ArrayList<>();
        Map<String, Long> pendingRewards = new LinkedHashMap<>();
        long rotationEndsAt, lifetimeEarned;
        int rotationsDone;
    }
    private final Path file;
    private final Logger logger;
    private Data data = new Data();
    private boolean dirty, healthy = true;
    private java.util.function.BooleanSupplier financeBusy = () -> false;
    public synchronized void bindFinance(java.util.function.BooleanSupplier busy) { financeBusy = Objects.requireNonNull(busy); }
    private final ru.cherdakmd.chrdkregister.api.ChargeJournal.WriteCheckpoint checkpoint;
    public GoalStore(Path folder, Logger logger) { this(folder, logger, () -> { }); }
    GoalStore(Path folder, Logger logger, ru.cherdakmd.chrdkregister.api.ChargeJournal.WriteCheckpoint checkpoint) {
        file = folder.resolve("goals-data.json"); this.logger = logger; this.checkpoint = Objects.requireNonNull(checkpoint);
    }
    public synchronized boolean healthy() { return healthy; }
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            var object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            ru.cherdakmd.chrdkregister.api.FinanceNumbers.requireIntegral(object);
            for (String key : List.of("goals", "pendingRewards", "rotationEndsAt", "rotationsDone", "lifetimeEarned"))
                if (!object.has(key)) throw new IllegalArgumentException("missing " + key);
            var loaded = JSON.fromJson(object, Data.class);
            if (loaded.schema == 2) for (String key : List.of("incomeId", "bankRegistered"))
                if (!object.has(key)) throw new IllegalArgumentException("missing " + key);
            validate(loaded); data = loaded;
        } catch (Exception error) { healthy = false; logger.log(Level.SEVERE, "Цели: источник повреждён, изменения и выплаты остановлены; файл не сброшен", error); }
    }
    private static void validate(Data d) {
        if (d == null || d.schema != 1 && d.schema != 2 || d.schema == 2 && d.incomeId == null
                || d.goals == null || d.pendingRewards == null || d.rotationEndsAt < 0 || d.rotationsDone < 0 || d.lifetimeEarned < 0)
            throw new IllegalArgumentException("goals data");
        var ids = new HashSet<String>();
        for (var goal : d.goals) {
            if (goal == null || goal.id == null || !ids.add(goal.id) || goal.target < 1 || goal.rewardPool < 0
                    || goal.progress < 0 || goal.progress > goal.target || goal.contributions == null)
                throw new IllegalArgumentException("goal");
            for (var row : goal.contributions.entrySet()) if (!name(row.getKey()) || row.getValue() == null || row.getValue() < 0)
                throw new IllegalArgumentException("contribution");
        }
        for (var row : d.pendingRewards.entrySet()) if (!name(row.getKey()) || row.getValue() == null || row.getValue() <= 0)
            throw new IllegalArgumentException("income");
    }
    private static boolean name(String n) { return n != null && n.matches("[a-z0-9_]{1,16}"); }
    private void writable() { if (!healthy || financeBusy.getAsBoolean()) throw new IllegalStateException("goals finance paused"); }
    private void persist(Data next) throws IOException {
        if (!healthy) throw new IllegalStateException("goals storage quarantined"); validate(next);
        try {
            Files.createDirectories(file.getParent()); Path temp = file.resolveSibling("goals-data.json.tmp");
            Files.writeString(temp, JSON.toJson(next), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        checkpoint.afterReplace();
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            data = next; dirty = false;
        } catch (IOException error) { healthy = false; throw error; }
    }
    private Data copy() { return JSON.fromJson(JSON.toJson(data), Data.class); }
    public synchronized void enableBank() throws IOException {
        writable(); if (data.schema == 2) return;
        var next = copy(); next.schema = 2; next.incomeId = UUID.randomUUID(); persist(next);
    }
    @Override public synchronized String snapshot() {
        if (!healthy) throw new IllegalStateException("goals storage quarantined"); return JSON.toJson(data);
    }
    /** Только preview: завершение, вклад и распределённые награды подготовлены без изменения source. */
    public synchronized String previewDonation(String name, int amount, int bonus) throws IOException {
        writable(); saveStrict();
        if (data.schema != 2 || !data.bankRegistered || name == null || !name(name) || amount < 1 || amount > 100_000)
            throw new IllegalArgumentException("bank donation");
        var next = copy(); var goal = next.goals.stream().filter(g -> g.id.equals("donate-diamonds") && !g.done).findFirst().orElseThrow();
        goal.progress = (int) Math.min(goal.target, (long) goal.progress + amount);
        goal.contributions.merge(name, amount, Math::addExact);
        if (goal.progress == goal.target) {
            goal.done = true;
            for (var row : GoalRewards.distribute(goal.rewardPool, goal.contributions, bonus).entrySet()) {
                next.pendingRewards.merge(row.getKey(), (long) row.getValue(), Math::addExact);
                next.lifetimeEarned = Math.addExact(next.lifetimeEarned, row.getValue());
            }
        }
        validate(next); return JSON.toJson(next);
    }
    @Override public synchronized void publish(String before, String after) throws IOException {
        if (!healthy) throw new IOException("goals storage quarantined");
        String current = snapshot(); if (current.equals(after)) return;
        if (!current.equals(before)) throw new IllegalStateException("goals state conflict");
        var object = JsonParser.parseString(after); ru.cherdakmd.chrdkregister.api.FinanceNumbers.requireIntegral(object);
        var next = JSON.fromJson(object, Data.class); validate(next);
        if (next.schema != 2 || !next.bankRegistered || !Objects.equals(data.incomeId, next.incomeId) || !after.equals(JSON.toJson(next)))
            throw new IllegalArgumentException("goals state identity/canonical encoding");
        persist(next);
    }
    public synchronized UUID incomeId() { return data.incomeId; }
    public synchronized boolean bankRegistered() { return data.bankRegistered; }
    public synchronized void markBankRegistered() throws IOException { writable(); var next = copy(); next.bankRegistered = true; persist(next); }
    public synchronized void saveStrict() throws IOException { if (dirty) persist(copy()); }
    public synchronized void save() {
        if (!healthy || !dirty) return;
        try { saveStrict(); } catch (IOException error) { logger.log(Level.SEVERE, "Цели: ошибка записи, хранилище остановлено", error); }
    }
    public void saveAsync(CommonGoalsPlugin plugin) { plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save); }
    public synchronized void markDirty() { if (healthy && !financeBusy.getAsBoolean()) dirty = true; }
    public synchronized List<GoalState> goals() { return List.copyOf(data.goals); }
    public synchronized Optional<GoalState> goal(String id) { return data.goals.stream().filter(g -> g.id.equals(id)).findFirst(); }
    public synchronized void replaceGoals(List<GoalState> goals, long ends) {
        writable(); data.goals = new ArrayList<>(goals); data.rotationEndsAt = ends; data.rotationsDone = Math.incrementExact(data.rotationsDone); dirty = true;
    }
    public synchronized long rotationEndsAt() { return data.rotationEndsAt; }
    public synchronized int rotationsDone() { return data.rotationsDone; }
    public synchronized boolean needRotation(long now) { return healthy && !financeBusy.getAsBoolean() && (data.goals.isEmpty() || data.rotationEndsAt <= now); }
    public synchronized boolean addProgress(String id, String player, int amount) {
        writable(); var found = goal(id); if (found.isEmpty() || found.get().done || amount <= 0) return false;
        var g = found.get(); g.progress = (int) Math.min(g.target, (long) g.progress + amount);
        if (player != null) g.contributions.merge(player.toLowerCase(Locale.ROOT), amount, Math::addExact);
        dirty = true; if (g.progress < g.target) return false; g.done = true; return true;
    }
    /** Завершение, распределение и durable-source сохраняются под одним замком, до банковских вызовов. */
    public synchronized boolean progressAndRewards(String id, String player, int amount, int bonus) throws IOException {
        writable(); var previous = copy();
        try {
            if (!addProgress(id, player, amount)) return false;
            var g = goal(id).orElseThrow();
            for (var row : GoalRewards.distribute(g.rewardPool, g.contributions, bonus).entrySet()) addPendingReward(row.getKey(), row.getValue());
            saveStrict(); return true;
        } catch (IOException | RuntimeException error) {
            data = previous; // При IO остаётся quarantine: не перезаписываем неоднозначный диск старым состоянием.
            throw error;
        }
    }
    public synchronized void setProgress(String id, int value) {
        writable(); goal(id).ifPresent(g -> { g.progress = Math.max(0, Math.min(value, g.target)); g.done = g.progress >= g.target; dirty = true; });
    }
    public synchronized void addPendingReward(String player, int amount) {
        writable(); if (amount <= 0) return;
        String key = player.toLowerCase(Locale.ROOT); if (!name(key)) throw new IllegalArgumentException("player name");
        long next = Math.addExact(data.pendingRewards.getOrDefault(key, 0L), amount);
        long earned = Math.addExact(data.lifetimeEarned, amount);
        data.pendingRewards.put(key, next); data.lifetimeEarned = earned; dirty = true;
    }
    public synchronized int pendingRewardOf(String player) { return (int) Math.min(Integer.MAX_VALUE, entitlementOf(player)); }
    public synchronized long entitlementOf(String player) { return data.pendingRewards.getOrDefault(player.toLowerCase(Locale.ROOT), 0L); }
    /** Только для чтения старой схемы; production после enableBank сюда не попадает. */
    public synchronized int claim(String player) {
        writable(); if (data.schema == 2) throw new IllegalStateException("bank entitlement cannot be withdrawn as items");
        var amount = data.pendingRewards.remove(player.toLowerCase(Locale.ROOT)); dirty = true; return amount == null ? 0 : Math.toIntExact(amount);
    }
    public synchronized Map<String, Integer> pendingView() {
        var result = new LinkedHashMap<String, Integer>(); data.pendingRewards.forEach((n, a) -> result.put(n, Math.toIntExact(a))); return result;
    }
    public synchronized Map<String, Long> entitlements() { return Map.copyOf(data.pendingRewards); }
    public synchronized long lifetimeEarned() { return data.lifetimeEarned; }
}
