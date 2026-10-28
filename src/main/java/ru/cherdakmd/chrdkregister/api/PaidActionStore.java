package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** API 18. Durable-резерв услуги: READY ещё не вызывался, EXECUTING — исход требует доказательства.
 * Известный отказ увеличивает cumulative refund, а не делает blind deposit/выдачу предметов. */
public final class PaidActionStore implements ChargeInventory.Source {
    public enum Phase { READY, EXECUTING }
    public record Action(UUID id, UUID payer, UUID actor, int price, String kind, String payload, Phase phase) { }
    public record Resolution(UUID action, UUID payer, int price, boolean success, String operator, long at) { }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final class Data {
        int schema = 1;
        UUID lineage = UUID.randomUUID();
        boolean registered, valuesInitialized;
        Map<String,String> values = new LinkedHashMap<>();
        Map<UUID,Action> actions = new LinkedHashMap<>();
        Map<UUID,Long> refunds = new LinkedHashMap<>();
        List<Resolution> resolutions = new ArrayList<>();
    }
    private final Path file;
    private final ChargeJournal.WriteCheckpoint checkpoint;
    private Data data;
    private boolean healthy = true;
    private BooleanSupplier busy = () -> false;
    public PaidActionStore(Path folder) throws IOException { this(folder, () -> { }); }
    public PaidActionStore(Path folder, ChargeJournal.WriteCheckpoint checkpoint) throws IOException {
        file = folder.resolve("paid-actions.json"); this.checkpoint = Objects.requireNonNull(checkpoint);
        if (!Files.exists(file)) { write(new Data()); return; }
        try {
            if (Files.size(file) > 32L*1024*1024) throw new IllegalArgumentException("source capacity");
            var object = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); FinanceNumbers.requireIntegral(object);
            for (String key:List.of("schema","lineage","registered","valuesInitialized","values","actions","refunds","resolutions"))
                if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException("missing/null " + key);
            data = JSON.fromJson(object,Data.class); validate(data);
        } catch (RuntimeException error) { healthy=false; throw new IOException("Повреждён paid-actions.json; источник не сбрасывается",error); }
    }
    private static void validate(Data d) {
        if (d == null || d.schema != 1 || d.lineage == null || d.values == null || d.actions == null || d.refunds == null || d.resolutions == null || d.resolutions.size()>100)
            throw new IllegalArgumentException("action source");
        if (!d.registered && (!d.actions.isEmpty() || !d.refunds.isEmpty() || !d.resolutions.isEmpty()))
            throw new IllegalArgumentException("financial source lost registration marker");
        for (var row:d.values.entrySet()) if (row.getKey()==null || row.getKey().length()>160 || row.getValue()==null || row.getValue().length()>8*1024*1024)
            throw new IllegalArgumentException("source value");
        for (var row:d.actions.entrySet()) {
            var a=row.getValue();
            if (a==null || !Objects.equals(a.id(),row.getKey()) || a.payer()==null || a.actor()==null || a.price()<1 || a.price()>1_000_000
                    || a.phase()==null || a.kind()==null || !a.kind().matches("[a-z-]{1,32}") || a.payload()==null || a.payload().length()>16_384)
                throw new IllegalArgumentException("reservation");
        }
        for (var row:d.refunds.entrySet()) if (row.getKey()==null || row.getValue()==null || row.getValue()<1) throw new IllegalArgumentException("refund");
        for (var r:d.resolutions) if (r==null || r.action()==null || r.payer()==null || r.price()<1 || r.operator()==null || r.operator().isBlank() || r.at()<1)
            throw new IllegalArgumentException("resolution");
    }
    public synchronized UUID lineage() { return data.lineage; }
    public synchronized boolean healthy() { return healthy; }
    public synchronized boolean registered() { return data.registered; }
    public synchronized void bind(BooleanSupplier busy) { this.busy=Objects.requireNonNull(busy); }
    private void writable() { if (!healthy || busy.getAsBoolean()) throw new IllegalStateException("paid actions paused"); }
    private Data copy() { return JSON.fromJson(JSON.toJson(data),Data.class); }
    private void write(Data next) throws IOException {
        if (!healthy) throw new IOException("paid actions quarantined"); validate(next);
        try {
            Files.createDirectories(file.getParent()); var temp=file.resolveSibling("paid-actions.json.tmp");
            byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if (bytes.length>32*1024*1024) throw new IllegalArgumentException("source capacity");
            Files.write(temp,bytes);
            try (var c=FileChannel.open(temp,StandardOpenOption.WRITE)) { c.force(true); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            checkpoint.afterReplace();
            try (var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)) { c.force(true); }
            data=next;
        } catch (IOException | RuntimeException error) { healthy=false; throw error; }
    }
    public synchronized void markRegistered() throws IOException { writable(); var n=copy(); n.registered=true; write(n); }
    public synchronized boolean valuesInitialized() { return data.valuesInitialized; }
    public synchronized void initializeValues(Map<String,String> values) throws IOException {
        writable(); if (data.valuesInitialized) return; var n=copy(); n.values.putAll(values); n.valuesInitialized=true; write(n);
    }
    public synchronized Map<String,String> values() { return Map.copyOf(data.values); }
    public synchronized void putValue(String key,String value) throws IOException { writable(); var n=copy(); if (value==null) n.values.remove(key); else n.values.put(key,value); write(n); }
    /** API 19: доменная выплата и cumulative entitlement фиксируются одним source replace.
     * Получатель не становится владельцем всей казны: только явно снятой суммы. */
    public synchronized void creditValue(String key, String value, UUID owner, long amount) throws IOException {
        writable();
        if (!data.registered || owner == null || amount <= 0 || value == null) throw new IllegalArgumentException("domain credit");
        var next = copy();
        next.values.put(key, value);
        next.refunds.merge(owner, amount, Math::addExact);
        write(next);
    }
    public synchronized String previewValue(String key,String value) {
        writable(); if (!data.registered) throw new IllegalStateException("unregistered"); var n=copy(); n.values.put(key,value); validate(n); return JSON.toJson(n);
    }
    public synchronized String preview(Action action) {
        writable(); if (!data.registered || action.phase()!=Phase.READY || data.actions.containsKey(action.id()) || occupied(action.payer()) || occupied(action.actor()))
            throw new IllegalStateException("occupied reservation");
        var n=copy(); n.actions.put(action.id(),action); validate(n); return JSON.toJson(n);
    }
    public synchronized boolean occupied(UUID player) { return data.actions.values().stream().anyMatch(a->a.payer().equals(player)||a.actor().equals(player)); }
    public synchronized List<Action> actions() { return List.copyOf(data.actions.values()); }
    public synchronized List<Resolution> resolutions() { return List.copyOf(data.resolutions); }
    public synchronized Map<UUID,Long> refunds() { return Map.copyOf(data.refunds); }
    public synchronized boolean begin(UUID id) throws IOException {
        writable(); var a=data.actions.get(id); if (a==null || a.phase()!=Phase.READY) return false;
        var n=copy(); n.actions.put(id,new Action(a.id(),a.payer(),a.actor(),a.price(),a.kind(),a.payload(),Phase.EXECUTING)); write(n); return true;
    }
    public synchronized boolean finish(UUID id, boolean success) throws IOException {
        writable(); var a=data.actions.get(id); if (a==null || a.phase()!=Phase.EXECUTING) return false;
        return settle(a,success,null);
    }
    public synchronized boolean refundReady(UUID id) throws IOException {
        writable(); var a=data.actions.get(id); if (a==null || a.phase()!=Phase.READY) return false; return settle(a,false,null);
    }
    public synchronized boolean resolve(UUID id, boolean success, String operator) throws IOException {
        writable(); var a=data.actions.get(id); if (a==null || a.phase()!=Phase.EXECUTING) return false;
        if (operator==null || operator.isBlank()) throw new IllegalArgumentException("operator"); return settle(a,success,operator);
    }
    private boolean settle(Action a,boolean success,String operator) throws IOException {
        var n=copy(); n.actions.remove(a.id());
        if (!success) n.refunds.merge(a.payer(),(long)a.price(),Math::addExact);
        if (operator!=null) { n.resolutions.add(new Resolution(a.id(),a.payer(),a.price(),success,operator,System.currentTimeMillis())); if (n.resolutions.size()>100) n.resolutions.removeFirst(); }
        write(n); return true;
    }
    @Override public synchronized String snapshot() { if (!healthy) throw new IllegalStateException("source quarantined"); return JSON.toJson(data); }
    @Override public synchronized void publish(String before,String after) throws IOException {
        String current=snapshot(); if (current.equals(after)) return; if (!current.equals(before)) throw new IllegalStateException("source conflict");
        var object=JsonParser.parseString(after); FinanceNumbers.requireIntegral(object); var next=JSON.fromJson(object,Data.class); validate(next);
        if (!next.lineage.equals(data.lineage) || !next.registered || !after.equals(JSON.toJson(next))) throw new IllegalArgumentException("source identity/encoding");
        write(next);
    }
}
