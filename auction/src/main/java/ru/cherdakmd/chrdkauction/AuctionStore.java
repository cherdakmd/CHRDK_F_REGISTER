package ru.cherdakmd.chrdkauction;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/** Single-threaded ledger with a durable write-ahead inventory transfer. No Bukkit dependency. */
public final class AuctionStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_PRICE = 1_000_000;
    public static final int MAX_PAYLOAD = 90_000;
    public static final int MAX_ENTRIES = 5000;
    public record Lot(long id, UUID seller, String name, String item, int price, int fee, long expires) { }
    public record Parcel(long id, String item) { }
    public record Pending(UUID player, String operation, String before, String after, State next) { }
    public static final class State {
        long sequence = 1;
        LinkedHashMap<Long, Lot> lots = new LinkedHashMap<>();
        LinkedHashMap<UUID, ArrayList<Parcel>> mail = new LinkedHashMap<>();
        LinkedHashMap<UUID, Long> money = new LinkedHashMap<>();
    }
    private static final class Envelope {
        int schema = 1;
        State state = new State();
        Pending pending;
    }
    private final Path file;
    private Envelope data = new Envelope();
    public AuctionStore(Path dir, Logger logger) { file = dir.resolve("market.json"); }
    public void load() throws IOException {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 64L * 1024 * 1024) throw new IllegalArgumentException("ledger too large");
            JsonObject obj = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!obj.has("schema") || !obj.has("state")) throw new IllegalArgumentException("missing fields");
            requireStateFields(obj.getAsJsonObject("state"));
            if (obj.has("pending") && !obj.get("pending").isJsonNull())
                requireStateFields(obj.getAsJsonObject("pending").getAsJsonObject("next"));
            Envelope loaded = JSON.fromJson(obj, Envelope.class);
            if (loaded.schema != 1) throw new IllegalArgumentException("schema");
            validate(loaded.state);
            if (loaded.pending != null) {
                Pending p = loaded.pending;
                if (p.player == null || p.operation == null || p.before == null || p.after == null)
                    throw new IllegalArgumentException("pending");
                validate(p.next);
            }
            data = loaded;
        } catch (RuntimeException e) { throw new IOException("Corrupt auction ledger; restore a backup, do not delete it", e); }
    }
    private static void requireStateFields(JsonObject obj) {
        if (obj == null) throw new IllegalArgumentException("missing state");
        for (String key : List.of("sequence", "lots", "mail", "money"))
            if (!obj.has(key) || obj.get(key).isJsonNull()) throw new IllegalArgumentException("missing " + key);
    }
    private static void validate(State s) {
        if (s == null || s.sequence < 1 || s.lots == null || s.mail == null || s.money == null)
            throw new IllegalArgumentException("state");
        Set<Long> ids = new HashSet<>();
        int entries = s.lots.size();
        for (var e : s.lots.entrySet()) {
            Lot l = e.getValue();
            if (l == null || e.getKey() != l.id || l.id <= 0 || l.id >= s.sequence || l.seller == null
                    || l.name == null || l.name.isBlank() || l.price < 2 || l.price > MAX_PRICE
                    || l.fee < 0 || l.fee >= l.price || l.expires <= 0 || !ids.add(l.id))
                throw new IllegalArgumentException("lot");
            payload(l.item);
        }
        for (var e : s.mail.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw new IllegalArgumentException("mail");
            entries += e.getValue().size();
            for (Parcel p : e.getValue()) {
                if (p == null || p.id <= 0 || p.id >= s.sequence || !ids.add(p.id)) throw new IllegalArgumentException("parcel");
                payload(p.item);
            }
        }
        if (entries > MAX_ENTRIES) throw new IllegalArgumentException("market capacity");
        for (var e : s.money.entrySet()) if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0)
            throw new IllegalArgumentException("money");
    }
    private static void payload(String s) {
        if (s == null || s.isBlank() || s.length() > MAX_PAYLOAD) throw new IllegalArgumentException("item payload size");
        Base64.getDecoder().decode(s);
    }
    private State copy() { return JSON.fromJson(JSON.toJson(data.state), State.class); }
    private void write(Envelope next) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bytes = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("ledger capacity");
        Path temp = file.resolveSibling("market.json.tmp");
        Files.write(temp, bytes);
        try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) { ch.force(true); }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        data = next;
    }
    private void available() { if (data.pending != null) throw new IllegalStateException("recovery required"); }
    private void prepare(UUID player, String op, String before, String after, State next) throws IOException {
        available(); validate(next);
        if (player == null || before == null || after == null || before.equals(after)) throw new IllegalArgumentException("transfer");
        Envelope env = new Envelope(); env.state = data.state;
        env.pending = new Pending(player, op, before, after, next);
        write(env);
    }
    public static int fee(int price, int percent) {
        if (price < 2 || price > MAX_PRICE || percent < 0 || percent > 50) throw new IllegalArgumentException("price/fee");
        return (int) (((long) price * percent + 99) / 100);
    }
    public long prepareListing(UUID seller, String name, String item, int price, int percent, long expires,
                               long now, int perPlayer, int maxLots, String before, String after) throws IOException {
        available();
        if (expires <= now || data.state.lots.size() >= maxLots
                || data.state.lots.values().stream().filter(l -> l.seller.equals(seller)).count() >= perPlayer)
            throw new IllegalArgumentException("listing limit");
        State next = copy(); long id = next.sequence++;
        next.lots.put(id, new Lot(id, seller, name, item, price, fee(price, percent), expires));
        prepare(seller, "LIST #" + id, before, after, next); return id;
    }
    public void prepareBuy(UUID buyer, long id, long now, String before, String after) throws IOException {
        available(); Lot lot = lot(id);
        if (lot == null || lot.expires <= now || lot.seller.equals(buyer)) throw new IllegalArgumentException("unavailable lot");
        State next = copy(); next.lots.remove(id);
        next.mail.computeIfAbsent(buyer, k -> new ArrayList<>()).add(new Parcel(id, lot.item));
        next.money.merge(lot.seller, (long) lot.price - lot.fee, Math::addExact);
        prepare(buyer, "BUY #" + id, before, after, next);
    }
    public void cancel(UUID seller, long id) throws IOException {
        available(); Lot l = lot(id);
        if (l == null || !l.seller.equals(seller)) throw new IllegalArgumentException("not your lot");
        State next = copy(); next.lots.remove(id);
        next.mail.computeIfAbsent(seller, k -> new ArrayList<>()).add(new Parcel(id, l.item)); publish(next);
    }
    public void expire(long now) throws IOException {
        available(); State next = copy(); boolean changed = false;
        for (Lot l : new ArrayList<>(next.lots.values())) if (l.expires <= now) {
            next.lots.remove(l.id);
            next.mail.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(new Parcel(l.id, l.item)); changed = true;
        }
        if (changed) publish(next);
    }
    private void publish(State next) throws IOException {
        validate(next); Envelope env = new Envelope(); env.state = next; write(env);
    }
    public void prepareItemClaim(UUID owner, long id, String before, String after) throws IOException {
        available(); State next = copy(); var parcels = next.mail.get(owner);
        if (parcels == null || !parcels.removeIf(p -> p.id == id)) throw new IllegalArgumentException("missing parcel");
        if (parcels.isEmpty()) next.mail.remove(owner);
        prepare(owner, "ITEM #" + id, before, after, next);
    }
    public void prepareMoneyClaim(UUID owner, int amount, String before, String after) throws IOException {
        available(); long balance = balance(owner);
        if (amount <= 0 || amount > balance) throw new IllegalArgumentException("balance");
        State next = copy();
        if (amount == balance) next.money.remove(owner); else next.money.put(owner, balance - amount);
        prepare(owner, "WITHDRAW " + amount, before, after, next);
    }
    public void complete() throws IOException {
        if (data.pending == null) throw new IllegalStateException("no transfer");
        publish(data.pending.next);
    }
    public void rollback() throws IOException {
        if (data.pending == null) throw new IllegalStateException("no transfer");
        publish(data.state);
    }
    public void validateItems(java.util.function.Consumer<String> validator) {
        validateItems(data.state, validator);
        if (data.pending != null) {
            validateItems(data.pending.next, validator);
            // Inventory snapshots are checked by the server codec during recovery.
        }
    }
    private static void validateItems(State state, java.util.function.Consumer<String> validator) {
        for (Lot l : state.lots.values()) validator.accept(l.item);
        for (var list : state.mail.values()) for (Parcel p : list) validator.accept(p.item);
    }
    public Pending pending() { return data.pending; }
    public Lot lot(long id) { return data.state.lots.get(id); }
    public List<Lot> lots() { return List.copyOf(data.state.lots.values()); }
    public List<Parcel> mail(UUID player) { return List.copyOf(data.state.mail.getOrDefault(player, new ArrayList<>())); }
    public long balance(UUID player) { return data.state.money.getOrDefault(player, 0L); }
}
