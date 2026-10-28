package ru.cherdakmd.chrdklottery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.logging.Logger;

/** Durable single-threaded ledger. Mutations are published only after a successful file replacement.
 * Player keys are UUIDs, not names. Corrupt files fail closed (never start an empty economy).
 */
public final class LotteryStore {
    public static final int MAX_POT = 1_000_000;
    public static final int MAX_TICKETS = 1_000_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private Data data = new Data();
    private boolean healthy = true;
    public synchronized boolean healthy() { return healthy; }

    public record LastWinner(String playerName, int pot, int payout, long drawnAtEpochMillis) { }
    private static final class Data {
        int schema = 1;
        UUID incomeId;
        boolean bankRegistered;
        LinkedHashMap<String, Integer> tickets = new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        int pot;
        long lastDrawAt;
        LastWinner lastWinner;
    }
    public LotteryStore(Path folder, Logger logger) { file = folder.resolve("data.json"); }

    public synchronized void load() throws IOException {
        if (!Files.exists(file)) return;
        try {
            var json = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            ru.cherdakmd.chrdkregister.api.FinanceNumbers.requireIntegral(json);
            if (!json.isJsonObject()) throw new IllegalArgumentException("ledger object expected");
            for (String key : List.of("schema", "tickets", "pending", "names", "pot", "lastDrawAt"))
                if (!json.getAsJsonObject().has(key)) throw new IllegalArgumentException("missing " + key);
            Data loaded = GSON.fromJson(json, Data.class);
            if (loaded.schema == 2) for (String key : List.of("incomeId", "bankRegistered"))
                if (!json.getAsJsonObject().has(key)) throw new IllegalArgumentException("missing " + key);
            validate(loaded);
            data = loaded;
        } catch (IOException | RuntimeException exception) {
            healthy = false;
            throw new IOException("Invalid lottery ledger; restore data.json from a backup", exception);
        }
    }
    private static void validate(Data d) {
        if (d == null || (d.schema != 1 && d.schema != 2) || d.tickets == null || d.pending == null || d.names == null
                || d.pot < 0 || d.pot > MAX_POT || d.lastDrawAt < 0) throw new IllegalArgumentException("ledger");
        if (d.schema == 2 && d.incomeId == null) throw new IllegalArgumentException("income identity");
        long total = 0;
        for (var e : d.tickets.entrySet()) {
            UUID.fromString(e.getKey());
            if (e.getValue() == null || e.getValue() <= 0 || !d.names.containsKey(e.getKey()))
                throw new IllegalArgumentException("tickets");
            total += e.getValue();
        }
        if (total > MAX_TICKETS || (total == 0) != (d.pot == 0)) throw new IllegalArgumentException("pot/tickets");
        for (var e : d.pending.entrySet()) {
            UUID.fromString(e.getKey());
            if (e.getValue() == null || e.getValue() <= 0) throw new IllegalArgumentException("pending");
        }
    }
    private Data copy() { return GSON.fromJson(GSON.toJson(data), Data.class); }
    private void commit(Data next) throws IOException {
        if (!healthy) throw new IOException("lottery storage quarantined");
        validate(next);
        try {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("data.json.tmp");
        Files.writeString(temp, GSON.toJson(next), StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        data = next;
        } catch (IOException error) { healthy = false; throw error; }
    }
    public synchronized void purchase(UUID id, String name, int count, int paid) throws IOException {
        if (id == null || name == null || name.isBlank() || count <= 0 || paid <= 0) throw new IllegalArgumentException("positive purchase required");
        Data next = copy();
        next.tickets.merge(id.toString(), count, Math::addExact);
        next.names.put(id.toString(), name);
        next.pot = Math.addExact(next.pot, paid);
        commit(next);
    }
    /** Settle exactly once: clear tickets, credit pending prize and save result in one replacement. */
    public synchronized LastWinner draw(long at, int percent, Random random) throws IOException {
        if (at <= data.lastDrawAt) throw new IllegalArgumentException("draw must advance clock");
        Data next = copy();
        String winner = LotteryRound.pickWinner(next.tickets, random);
        LastWinner result = null;
        if (winner != null) {
            int payout = LotteryRound.payoutOf(next.pot, percent);
            result = new LastWinner(displayName(winner), next.pot, payout, at);
            if (payout > 0) next.pending.merge(winner, (long) payout, Math::addExact);
            next.lastWinner = result;
        }
        next.tickets.clear();
        next.pot = 0;
        next.lastDrawAt = at;
        commit(next);
        return result;
    }
    /** Mark past boundary on first install or when catch-up is disabled. Keep prepaid tickets. */
    public synchronized void markDrawnNow(long at) throws IOException {
        if (at <= data.lastDrawAt) return;
        Data next = copy();
        next.lastDrawAt = at;
        commit(next);
    }
    public synchronized void debitPrize(UUID id, int amount) throws IOException {
        if (data.schema == 2) throw new IllegalStateException("bank entitlement cannot be withdrawn as items");
        long have = entitlementOf(id);
        if (amount <= 0 || amount > have) throw new IllegalArgumentException("prize debit");
        Data next = copy();
        if (amount == have) next.pending.remove(id.toString());
        else next.pending.put(id.toString(), have - amount);
        commit(next);
    }
    public synchronized void enableBank() throws IOException {
        if (data.schema == 2) return;
        Data next = copy(); next.schema = 2; next.incomeId = UUID.randomUUID(); commit(next);
    }
    public synchronized UUID incomeId() { return data.incomeId; }
    public synchronized boolean bankRegistered() { return data.bankRegistered; }
    public synchronized void markBankRegistered() throws IOException { Data next = copy(); next.bankRegistered = true; commit(next); }
    public synchronized long entitlementOf(UUID id) { return data.pending.getOrDefault(id.toString(), 0L); }
    public synchronized Map<String, Long> entitlements() { return Map.copyOf(data.pending); }
    public synchronized int ticketsOf(UUID id) { return data.tickets.getOrDefault(id.toString(), 0); }
    public synchronized int pendingPayoutOf(UUID id) { return (int) Math.min(Integer.MAX_VALUE, entitlementOf(id)); }
    public synchronized Map<String, Integer> ticketsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(data.tickets));
    }
    public synchronized String displayName(String id) { return data.names.getOrDefault(id, id); }
    public synchronized int pot() { return data.pot; }
    public synchronized long lastDrawAt() { return data.lastDrawAt; }
    public synchronized LastWinner lastWinner() { return data.lastWinner; }
}
