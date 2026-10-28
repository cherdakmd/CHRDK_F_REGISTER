package ru.cherdakmd.chrdkregister.api;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** API 20: неизменяемая цена once-награды и денежное обязательство в одном replace.
 * Это журнал наград, не расходуемый кошелёк; старые выданные вехи импортируются с нулём. */
public final class MilestoneStore {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final class Data {
        int schema=1;
        UUID lineage=UUID.randomUUID();
        String source;
        boolean imported,registered;
        Map<UUID,Map<String,Long>> awards=new LinkedHashMap<>();
    }
    private final Path file;
    private final String source;
    private final ChargeJournal.WriteCheckpoint checkpoint;
    private Data data;
    private boolean healthy=true;
    public MilestoneStore(Path folder,String source) throws IOException { this(folder,source,()->{}); }
    public MilestoneStore(Path folder,String source,ChargeJournal.WriteCheckpoint checkpoint) throws IOException {
        if(source==null||!source.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("award namespace");
        this.source=source; file=folder.resolve("milestone-awards.json"); this.checkpoint=Objects.requireNonNull(checkpoint);
        if (!Files.exists(file)) { var fresh=new Data(); fresh.source=source; write(fresh); return; }
        try {
            if (Files.size(file)>32L*1024*1024) throw new IllegalArgumentException("award capacity");
            var object=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); FinanceNumbers.requireIntegral(object);
            for(String key:List.of("schema","source","lineage","imported","registered","awards"))
                if(!object.has(key)||object.get(key).isJsonNull()) throw new IllegalArgumentException("missing "+key);
            if(!object.get("schema").isJsonPrimitive()||!object.get("schema").getAsJsonPrimitive().isNumber()
                    ||!object.get("registered").isJsonPrimitive()||!object.get("registered").getAsJsonPrimitive().isBoolean()
                    ||!object.get("imported").isJsonPrimitive()||!object.get("imported").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("award metadata type");
            MilestoneMigration.uuid(object.get("lineage").getAsString());
            for(var player:object.getAsJsonObject("awards").entrySet()) {
                MilestoneMigration.uuid(player.getKey());
                for(var award:player.getValue().getAsJsonObject().entrySet()) {
                    if(!award.getValue().isJsonPrimitive()||!award.getValue().getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("award amount type");
                }
            }
            data=JSON.fromJson(object,Data.class); validate(data);
            if(!source.equals(data.source)) throw new IllegalArgumentException("award source namespace");
        } catch(RuntimeException error) { healthy=false; throw new IOException("Повреждён milestone-awards.json; не сбрасывайте журнал наград",error); }
    }
    static void validKey(String key) {
        if(key==null||!key.matches("[a-z0-9][a-z0-9:_-]{0,119}")) throw new IllegalArgumentException("award key");
    }
    private static void validate(Data d) {
        if(d==null||d.schema!=1||d.source==null||!d.source.matches("[a-z][a-z0-9-]{0,47}")||d.lineage==null||d.awards==null||!d.imported&&(!d.awards.isEmpty()||d.registered)) throw new IllegalArgumentException("award source");
        for(var player:d.awards.entrySet()) {
            if(player.getKey()==null||player.getValue()==null||player.getValue().isEmpty()) throw new IllegalArgumentException("award owner");
            long total=0;
            for(var row:player.getValue().entrySet()) {
                validKey(row.getKey());
                if(row.getValue()==null||row.getValue()<0||row.getValue()>1_000_000) throw new IllegalArgumentException("award amount");
                total=Math.addExact(total,row.getValue());
            }
        }
    }
    private Data copy() { return JSON.fromJson(JSON.toJson(data),Data.class); }
    private void writable() { if(!healthy) throw new IllegalStateException("award source quarantined"); }
    private void write(Data next) throws IOException {
        writable(); validate(next);
        try {
            Files.createDirectories(file.getParent()); byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>32*1024*1024) throw new IllegalArgumentException("award capacity");
            var temp=file.resolveSibling("milestone-awards.json.tmp"); Files.write(temp,bytes);
            try(var c=FileChannel.open(temp,StandardOpenOption.WRITE)) { c.force(true); }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            checkpoint.afterReplace();
            try(var c=FileChannel.open(file.getParent(),StandardOpenOption.READ)) { c.force(true); }
            data=next;
        } catch(IOException error) { healthy=false; throw error; }
    }
    public synchronized boolean healthy() { return healthy; }
    public synchronized String source() { return source; }
    public synchronized UUID lineage() { return data.lineage; }
    public synchronized boolean imported() { return data.imported; }
    public synchronized boolean registered() { return data.registered; }
    public synchronized void markRegistered() throws IOException { writable(); if(data.registered) return; var n=copy(); n.registered=true; write(n); }
    public synchronized void initialize(Map<UUID,Set<String>> legacy) throws IOException {
        writable(); if(data.imported) return; var n=copy(); n.imported=true;
        for(var player:legacy.entrySet()) {
            if(player.getKey()==null||player.getValue()==null) throw new IllegalArgumentException("legacy owner");
            if(!player.getValue().isEmpty()) {
                var rows=new LinkedHashMap<String,Long>(); for(String key:player.getValue()) { validKey(key); rows.put(key,0L); }
                n.awards.put(player.getKey(),rows);
            }
        }
        write(n);
    }
    /** true = финансовое обязательство уже durable, в том числе при повторе/новой цене. */
    public synchronized boolean reserve(UUID owner,String key,long amount) throws IOException {
        validKey(key); return reserveAll(owner,Map.of(key,amount));
    }
    /** Один переход XP может закрыть несколько уровней: все once-записи публикуются вместе. */
    public synchronized boolean reserveAll(UUID owner,Map<String,Long> awards) throws IOException {
        writable();
        if(!data.imported||owner==null||awards==null) throw new IllegalArgumentException("award reservation");
        for(var row:awards.entrySet()) {
            validKey(row.getKey());
            if(row.getValue()==null||row.getValue()<0||row.getValue()>1_000_000) throw new IllegalArgumentException("award amount");
        }
        if(awards.isEmpty()||claimed(owner).containsAll(awards.keySet())) return true;
        var n=copy(); var rows=n.awards.computeIfAbsent(owner,id->new LinkedHashMap<>());
        awards.forEach(rows::putIfAbsent); write(n); return true;
    }
    /** -1 — веха ещё не принята; ноль также может означать историческую already-paid отметку. */
    public synchronized long amount(UUID owner,String key) { var rows=data.awards.get(owner); return rows==null?-1:rows.getOrDefault(key,-1L); }
    public synchronized Set<String> claimed(UUID owner) { var rows=data.awards.get(owner); return rows==null?Set.of():Set.copyOf(rows.keySet()); }
    public synchronized Map<UUID,Long> totals() {
        var totals=new LinkedHashMap<UUID,Long>();
        data.awards.forEach((owner,rows)->{long total=0; for(long amount:rows.values()) total=Math.addExact(total,amount); if(total>0) totals.put(owner,total);});
        return Collections.unmodifiableMap(totals);
    }
}
